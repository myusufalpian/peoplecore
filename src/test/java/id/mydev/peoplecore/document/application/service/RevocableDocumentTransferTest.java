package id.mydev.peoplecore.document.application.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevocableDocumentTransferTest {
    @Test
    void closingTransferServiceAbortsAnActiveTransfer() throws Exception {
        BlockingSource source = new BlockingSource();
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1));
             ExecutorService runner = Executors.newSingleThreadExecutor()) {
            Future<?> running = runner.submit(() -> assertThrows(IOException.class,
                () -> transfer.transfer(source, new ByteArrayOutputStream(), 1, () -> true)));
            assertTrue(source.readStarted.await(2, TimeUnit.SECONDS));
            transfer.close();
            assertTrue(source.closed.await(2, TimeUnit.SECONDS));
            running.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void requiresAuthorizationAgainAfterCopyCompletes() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(10), Duration.ofSeconds(1))) {
            assertThrows(IOException.class, () -> transfer.transfer(new ByteArrayInputStream(new byte[] {1}),
                new ByteArrayOutputStream(), 1, () -> checks.getAndIncrement() == 0));
        }
    }
    @Test
    void rejectsInvalidAuthorizationTimingAndConcurrency() {
        Duration second = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(0, second, second));
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(1, Duration.ofMillis(-1), second));
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(1, Duration.ZERO, second));
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(1, Duration.ofNanos(1), second));
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(1, second, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(1, second, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(1, second, Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(1, Duration.ofMinutes(6), second));
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(1, Duration.ofMinutes(4), Duration.ofMinutes(2)));
        assertThrows(IllegalArgumentException.class, () -> new RevocableDocumentTransfer(1, Duration.ofMinutes(3), Duration.ofSeconds(61)));
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofMinutes(3), Duration.ofMinutes(1))) {
            assertTrue(transfer != null);
        }
    }

    @Test
    void rejectsInvalidSizeAndAuthorizationFailures() throws Exception {
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            assertThrows(IllegalArgumentException.class, () -> transfer.transfer(new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), 0, () -> true));
            assertThrows(IllegalArgumentException.class, () -> transfer.transfer(new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), DocumentContentInspector.MAX_BYTES + 1, () -> true));
            assertThrows(IOException.class, () -> transfer.transfer(new ByteArrayInputStream(new byte[] {1}),
                new ByteArrayOutputStream(), 1, () -> { throw new IOException("Authorization unavailable"); }));
        }
    }

    @Test
    void refusesAnotherTransferWhileCapacityIsInUse() throws Exception {
        BlockingSource source = new BlockingSource();
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(1), Duration.ofSeconds(1));
             ExecutorService runner = Executors.newSingleThreadExecutor()) {
            Future<?> running = runner.submit(() -> assertThrows(IOException.class,
                () -> transfer.transfer(source, new ByteArrayOutputStream(), 1, () -> true)));
            try {
                assertTrue(source.readStarted.await(2, TimeUnit.SECONDS));
                assertThrows(IOException.class, () -> transfer.transfer(new ByteArrayInputStream(new byte[] {1}),
                    new ByteArrayOutputStream(), 1, () -> true));
            } finally {
                source.close();
            }
            running.get(2, TimeUnit.SECONDS);
        }
    }
    @Test
    void streamsOnlyTheVerifiedNumberOfBytesWhenAuthorizationRemainsValid() throws Exception {
        byte[] content = {1, 2, 3};
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofMillis(20), Duration.ofMillis(100))) {
            transfer.transfer(new ByteArrayInputStream(content), result, content.length, () -> true);
        }
        assertArrayEquals(content, result.toByteArray());
    }

    @Test
    void consecutiveValidTransfersDoNotRejectAuthorizationDuringWorkerHandoff() throws Exception {
        byte[] content = {1, 2, 3};
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofSeconds(10), Duration.ofSeconds(2))) {
            for (int index = 0; index < 500; index++) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                transfer.transfer(new ByteArrayInputStream(content), result, content.length, () -> true);
                assertArrayEquals(content, result.toByteArray());
            }
        }
    }

    @Test
    void deniedOrUnavailableAuthorizationClosesTheTransfer() throws Exception {
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofMillis(20), Duration.ofMillis(50))) {
            assertThrows(IOException.class, () -> transfer.transfer(new ByteArrayInputStream(new byte[] {1}),
                new ByteArrayOutputStream(), 1, () -> false));
            assertThrows(IOException.class, () -> transfer.transfer(new ByteArrayInputStream(new byte[] {1}),
                new ByteArrayOutputStream(), 1, () -> {
                    Thread.sleep(1000);
                    return true;
                }));
        }
    }

    @Test
    void revocationClosesAStalledSourceWithoutWaitingForAnotherChunk() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        BlockingSource source = new BlockingSource();
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofMillis(20), Duration.ofMillis(100));
             ExecutorService runner = Executors.newSingleThreadExecutor()) {
            Future<?> running = runner.submit(() -> {
                assertThrows(IOException.class, () -> transfer.transfer(source, new ByteArrayOutputStream(), 1, allowed::get));
            });
            assertTrue(source.readStarted.await(2, TimeUnit.SECONDS));
            allowed.set(false);
            assertTrue(source.closed.await(2, TimeUnit.SECONDS));
            running.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsTruncatedAndOversizedContent() throws Exception {
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofMillis(20), Duration.ofMillis(100))) {
            assertThrows(IOException.class, () -> transfer.transfer(new ByteArrayInputStream(new byte[] {1}),
                new ByteArrayOutputStream(), 2, () -> true));
            assertThrows(IOException.class, () -> transfer.transfer(new ByteArrayInputStream(new byte[] {1, 2}),
                new ByteArrayOutputStream(), 1, () -> true));
        }
    }

    @Test
    void revokedTransferDoesNotStartAWriteAfterAStalledReadReturns() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        InputStream source = new InputStream() {
            @Override
            public int read() throws IOException {
                reading.countDown();
                try {
                    closed.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                return 1;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                bytes[offset] = (byte) read();
                return 1;
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofMillis(20), Duration.ofMillis(100));
             ExecutorService runner = Executors.newSingleThreadExecutor()) {
            Future<?> running = runner.submit(() -> assertThrows(IOException.class,
                () -> transfer.transfer(source, result, 1, allowed::get)));
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            allowed.set(false);
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            running.get(2, TimeUnit.SECONDS);
        }
        assertArrayEquals(new byte[0], result.toByteArray());
    }

    @Test
    void revocationClosesAnOutputBlockedInAWrite() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        OutputStream destination = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                writing.countDown();
                try {
                    closed.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                throw new IOException("Destination closed");
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        try (var transfer = new RevocableDocumentTransfer(1, Duration.ofMillis(20), Duration.ofMillis(100));
             ExecutorService runner = Executors.newSingleThreadExecutor()) {
            Future<?> running = runner.submit(() -> assertThrows(IOException.class,
                () -> transfer.transfer(new ByteArrayInputStream(new byte[] {1}), destination, 1, allowed::get)));
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            allowed.set(false);
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            running.get(2, TimeUnit.SECONDS);
        }
    }

    private static final class BlockingSource extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                closed.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            throw new IOException("Source closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
