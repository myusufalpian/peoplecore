package id.mydev.peoplecore.document.infrastructure.scanner;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClamAvScannerTest {
    private static final String DIGEST = "a".repeat(64);
    private static final java.util.UUID GENERATION = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] DOCUMENT = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
    @TempDir Path temporary;

    @Test
    void cleanResponseInspectsExactlyTheBytesSentForScanning() throws Exception {
        try (ServerSocketChannel server = server(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var received = executor.submit(() -> serve(server, "stream: OK\0"));
            try (ClamAvScanner scanner = scanner(ready(), Duration.ofSeconds(5))) {
                var result = scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length);
                assertEquals(DOCUMENT.length, result.content().byteSize());
                assertEquals("engine", result.engineVersion());
                assertEquals("definitions", result.definitionVersion());
            }
            assertEquals(new String(DOCUMENT, StandardCharsets.US_ASCII), received.get());
        }
    }

    @Test
    void malwareAndMalformedResponsesNeverProduceCleanResult() throws Exception {
        assertScanFails("stream: Signature FOUND\0", ClamAvScanner.MalwareDetectedException.class);
        assertScanFails("stream: ERROR\0", IOException.class);
        assertScanFails("stream: OK", IOException.class);
        assertScanFails("stream: OK\0stream: Signature FOUND\0", IOException.class);
    }

    @Test
    void staleOrUnsafeScannerIsUnavailableWithoutConnecting() {
        var unsafe = new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, "engine", "definitions", NOW.plusSeconds(30), false, true);
        var expired = new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, "engine", "definitions", NOW, true, true);
        try (ClamAvScanner first = scanner(unsafe, Duration.ofSeconds(1));
             ClamAvScanner second = scanner(expired, Duration.ofSeconds(1))) {
            assertThrows(IOException.class, () -> first.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
            assertThrows(IOException.class, () -> second.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
        }
    }

    @Test
    void rejectsIncompleteOrUnsafeReadinessEvidence() {
        var statuses = new java.util.ArrayList<ClamAvScanner.ScannerStatus>();
        statuses.add(null);
        statuses.add(new ClamAvScanner.ScannerStatus(null, DIGEST, DIGEST, "engine", "definitions", NOW.plusSeconds(30), true, true));
        statuses.add(new ClamAvScanner.ScannerStatus(GENERATION, null, DIGEST, "engine", "definitions", NOW.plusSeconds(30), true, true));
        statuses.add(new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, "invalid", "engine", "definitions", NOW.plusSeconds(30), true, true));
        statuses.add(new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, null, "definitions", NOW.plusSeconds(30), true, true));
        statuses.add(new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, " ", "definitions", NOW.plusSeconds(30), true, true));
        statuses.add(new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, "engine", null, NOW.plusSeconds(30), true, true));
        statuses.add(new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, "engine", " ", NOW.plusSeconds(30), true, true));
        statuses.add(new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, "engine", "definitions", null, true, true));
        statuses.add(new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, "engine", "definitions", NOW.plusSeconds(30), true, false));
        for (var status : statuses) {
            try (var scanner = scanner(status, Duration.ofSeconds(1))) {
                assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
            }
        }
    }

    @Test
    void rejectsCleanResultWhenReadinessChangesDuringScan() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ServerSocketChannel server = server(); var executor = Executors.newVirtualThreadPerTaskExecutor();
             ClamAvScanner scanner = new ClamAvScanner(temporary.resolve("clamd.sock"), Duration.ofSeconds(5), 1,
                 () -> calls.getAndIncrement() == 0 ? ready()
                     : new ClamAvScanner.ScannerStatus(java.util.UUID.randomUUID(), DIGEST, DIGEST, "engine", "definitions", NOW.plusSeconds(60), true, true),
                 Clock.fixed(NOW, ZoneOffset.UTC))) {
            var work = executor.submit(() -> serve(server, "stream: OK\0"));
            assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
            work.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsChangedSnapshotEvenWhenGenerationAndVersionsAreUnchanged() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ServerSocketChannel server = server(); var executor = Executors.newVirtualThreadPerTaskExecutor();
             ClamAvScanner scanner = new ClamAvScanner(temporary.resolve("clamd.sock"), Duration.ofSeconds(5), 1,
                 () -> calls.getAndIncrement() == 0 ? ready()
                     : new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, "b".repeat(64), "engine", "definitions",
                         NOW.plusSeconds(60), true, true), Clock.fixed(NOW, ZoneOffset.UTC))) {
            var work = executor.submit(() -> serve(server, "stream: OK\0"));
            assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
            work.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void acceptsRefreshedEvidenceForSameGeneration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ServerSocketChannel server = server(); var executor = Executors.newVirtualThreadPerTaskExecutor();
             ClamAvScanner scanner = new ClamAvScanner(temporary.resolve("clamd.sock"), Duration.ofSeconds(5), 1,
                 () -> calls.getAndIncrement() == 0 ? ready()
                     : new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, "engine", "definitions",
                         NOW.plusSeconds(120), true, true), Clock.fixed(NOW, ZoneOffset.UTC))) {
            var work = executor.submit(() -> serve(server, "stream: OK\0"));
            scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length);
            work.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsOverlongScannerResponse() throws Exception {
        assertScanFails("x".repeat(4097), IOException.class);
    }

    @Test
    void rejectsInvalidScannerLimits() {
        assertThrows(IllegalArgumentException.class, () -> scanner(ready(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> scanner(ready(), Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new ClamAvScanner(temporary.resolve("clamd.sock"),
            Duration.ofSeconds(1), 0, ClamAvScannerTest::ready, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void timeoutCannotReturnClean() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocketChannel server = server(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var work = executor.submit(() -> {
                try (var ignored = server.accept()) {
                    release.await();
                }
                return null;
            });
            try (ClamAvScanner scanner = scanner(ready(), Duration.ofMillis(100))) {
                assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
            } finally {
                release.countDown();
            }
            work.get();
        }
    }

    @Test
    void readinessTimeoutCannotHangTheCallerOrReturnClean() {
        CountDownLatch wait = new CountDownLatch(1);
        try (ClamAvScanner scanner = new ClamAvScanner(temporary.resolve("clamd.sock"), Duration.ofMillis(100), 1,
            () -> {
                try {
                    wait.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", exception);
                }
                return ready();
            }, Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
        } finally {
            wait.countDown();
        }
    }

    @Test
    void timedOutReadinessCannotConnectAfterItReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        try (ServerSocketChannel server = server(); var executor = Executors.newVirtualThreadPerTaskExecutor();
             ClamAvScanner scanner = new ClamAvScanner(temporary.resolve("clamd.sock"), Duration.ofMillis(200), 1,
                 () -> {
                     started.countDown();
                     boolean waiting = true;
                     while (waiting) {
                         try {
                             release.await();
                             waiting = false;
                         } catch (InterruptedException ignored) {
                             // Model a readiness provider that ignores cancellation.
                         }
                     }
                     returned.countDown();
                     return ready();
                 }, Clock.fixed(NOW, ZoneOffset.UTC))) {
            var connection = executor.submit(() -> { return server.accept(); });
            try {
                assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                release.countDown();
                assertTrue(returned.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> connection.get(1, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                server.close();
            }
        }
    }

    @Test
    void timeoutReturnsEvenWhenClosingTheSourceBlocksAndClosesTheSocketIndependently() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        InputStream blocking = new InputStream() {
            @Override
            public int read() {
                readStarted.countDown();
                waitUninterruptibly(releaseRead);
                return -1;
            }

            @Override
            public void close() {
                closeStarted.countDown();
                waitUninterruptibly(releaseClose);
            }
        };
        try (ServerSocketChannel server = server(); var executor = Executors.newVirtualThreadPerTaskExecutor();
             ClamAvScanner scanner = scanner(ready(), Duration.ofMillis(500))) {
            var disconnected = executor.submit(() -> {
                try (var connection = server.accept()) {
                    var input = new DataInputStream(Channels.newInputStream(connection));
                    input.readNBytes(10);
                    try {
                        input.readInt();
                        return false;
                    } catch (IOException expected) {
                        return true;
                    }
                }
            });
            var result = executor.submit(() -> {
                try {
                    scanner.scan(blocking, "application/pdf", DOCUMENT.length);
                    return false;
                } catch (IOException expected) {
                    return true;
                }
            });
            try {
                assertTrue(readStarted.await(5, TimeUnit.SECONDS));
                assertTrue(result.get(5, TimeUnit.SECONDS));
                assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
                assertTrue(disconnected.get(5, TimeUnit.SECONDS));
                assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
            } finally {
                releaseRead.countDown();
                releaseClose.countDown();
            }
        }
    }

    private static void waitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertScanFails(String reply, Class<? extends IOException> failure) throws Exception {
        try (ServerSocketChannel server = server(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var work = executor.submit(() -> serve(server, reply));
            try (ClamAvScanner scanner = scanner(ready(), Duration.ofSeconds(5))) {
                assertThrows(failure, () -> scanner.scan(new ByteArrayInputStream(DOCUMENT), "application/pdf", DOCUMENT.length));
            }
            work.get();
        }
        java.nio.file.Files.deleteIfExists(temporary.resolve("clamd.sock"));
    }

    private ClamAvScanner scanner(ClamAvScanner.ScannerStatus status, Duration timeout) {
        return new ClamAvScanner(temporary.resolve("clamd.sock"), timeout, 1, () -> status, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ClamAvScanner.ScannerStatus ready() {
        return new ClamAvScanner.ScannerStatus(GENERATION, DIGEST, DIGEST, "engine", "definitions", NOW.plusSeconds(60), true, true);
    }

    private ServerSocketChannel server() throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(temporary.resolve("clamd.sock")));
        return server;
    }

    private static String serve(ServerSocketChannel server, String reply) throws IOException {
        try (var connection = server.accept()) {
            var input = new DataInputStream(Channels.newInputStream(connection));
            byte[] command = input.readNBytes(10);
            assertEquals("zINSTREAM\0", new String(command, StandardCharsets.US_ASCII));
            var content = new java.io.ByteArrayOutputStream();
            int size;
            while ((size = input.readInt()) != 0) {
                content.write(input.readNBytes(size));
            }
            Channels.newOutputStream(connection).write(reply.getBytes(StandardCharsets.US_ASCII));
            return content.toString(StandardCharsets.US_ASCII);
        }
    }
}
