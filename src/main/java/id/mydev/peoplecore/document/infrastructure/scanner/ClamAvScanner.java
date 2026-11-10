package id.mydev.peoplecore.document.infrastructure.scanner;

import id.mydev.peoplecore.document.application.service.DocumentContentInspector;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ClamAvScanner implements AutoCloseable {
    private static final byte[] COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_RESPONSE_BYTES = 4096;
    private final Path socket;
    private final Duration timeout;
    private final ScannerReadiness readiness;
    private final Clock clock;
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService sourceClosers;
    private final ExecutorService socketClosers;

    public ClamAvScanner(Path socket, Duration timeout, int maxConcurrent, ScannerReadiness readiness, Clock clock) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() < 1 || maxConcurrent < 1) {
            throw new IllegalArgumentException("Scanner timeout and concurrency must be positive");
        }
        this.permits = new Semaphore(maxConcurrent);
        this.sourceClosers = closeExecutor(maxConcurrent, "document-scanner-source-close-");
        this.socketClosers = closeExecutor(maxConcurrent, "document-scanner-socket-close-");
    }

    public Result scan(InputStream source, String mediaType, long size) throws IOException {
        Objects.requireNonNull(source, "source");
        if (!permits.tryAcquire()) {
            throw new IOException("Scanner is busy");
        }
        AtomicReference<SocketChannel> active = new AtomicReference<>();
        AtomicBoolean claimed = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        Future<Result> future;
        try {
            future = executor.submit(() -> {
                if (!claimed.compareAndSet(false, true)) {
                    throw new IOException("Scanner interrupted");
                }
                try {
                    ScannerStatus before = requireReady();
                    checkCancelled(cancelled);
                    DocumentContentInspector.Result content = scanStream(source, mediaType, size, active, cancelled);
                    checkCancelled(cancelled);
                    ScannerStatus after = requireReady();
                    checkCancelled(cancelled);
                    if (!sameGeneration(before, after) || !clock.instant().isBefore(before.validUntil())) {
                        throw new IOException("Scanner readiness changed during scanning");
                    }
                    return new Result(content, before.engineVersion(), before.definitionVersion());
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException exception) {
            permits.release();
            throw exception;
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            stop(source, active, future, cancelled, claimed);
            throw new IOException("Scanner timed out", exception);
        } catch (InterruptedException exception) {
            stop(source, active, future, cancelled, claimed);
            Thread.currentThread().interrupt();
            throw new IOException("Scanner interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException failure) {
                throw failure;
            }
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IOException("Scanner failed", cause);
        }
    }

    private DocumentContentInspector.Result scanStream(InputStream source, String mediaType, long size,
                                                        AtomicReference<SocketChannel> active,
                                                        AtomicBoolean cancelled) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            active.set(channel);
            checkCancelled(cancelled);
            channel.connect(UnixDomainSocketAddress.of(socket));
            checkCancelled(cancelled);
            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
            output.write(COMMAND);
            OutputStream chunks = new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    output.writeInt(1);
                    output.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (length > 0) {
                        output.writeInt(length);
                        output.write(bytes, offset, length);
                    }
                }
            };
            DocumentContentInspector.Result result = new DocumentContentInspector().inspect(source, chunks, mediaType, size);
            output.writeInt(0);
            output.flush();
            String reply = readReply(Channels.newInputStream(channel));
            if (reply.equals("stream: OK")) {
                return result;
            }
            if (reply.startsWith("stream: ") && reply.endsWith(" FOUND")) {
                throw new MalwareDetectedException();
            }
            throw new IOException("Scanner returned an indeterminate result");
        }
    }

    private static String readReply(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int count = 0; count < MAX_RESPONSE_BYTES; count++) {
            int next = input.read();
            if (next == -1) {
                throw new IOException("Scanner response was truncated");
            }
            if (next == 0) {
                if (input.read() != -1) {
                    throw new IOException("Scanner returned multiple responses");
                }
                return bytes.toString(StandardCharsets.US_ASCII);
            }
            bytes.write(next);
        }
        throw new IOException("Scanner response exceeded the limit");
    }

    private ScannerStatus requireReady() throws IOException {
        ScannerStatus status = readiness.current();
        if (status == null || status.generation() == null || status.configDigest() == null
            || !status.configDigest().matches("[0-9a-f]{64}") || status.snapshotDigest() == null
            || !status.snapshotDigest().matches("[0-9a-f]{64}") || status.engineVersion() == null || status.engineVersion().isBlank()
            || status.definitionVersion() == null || status.definitionVersion().isBlank()
            || status.validUntil() == null || !clock.instant().isBefore(status.validUntil())
            || !status.encryptedAlertEnabled() || !status.limitAlertEnabled()) {
            throw new IOException("Scanner readiness is unavailable");
        }
        return status;
    }

    private static boolean sameGeneration(ScannerStatus first, ScannerStatus second) {
        return first.generation().equals(second.generation()) && first.configDigest().equals(second.configDigest())
            && first.snapshotDigest().equals(second.snapshotDigest()) && first.engineVersion().equals(second.engineVersion())
            && first.definitionVersion().equals(second.definitionVersion());
    }

    private void stop(InputStream source, AtomicReference<SocketChannel> active, Future<?> future,
                      AtomicBoolean cancelled, AtomicBoolean claimed) {
        cancelled.set(true);
        future.cancel(true);
        if (claimed.compareAndSet(false, true)) {
            permits.release();
        }
        SocketChannel channel = active.get();
        if (channel != null) {
            closeAsync(socketClosers, channel);
        }
        closeAsync(sourceClosers, source);
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Scanner interrupted");
        }
    }

    private static ExecutorService closeExecutor(int maxConcurrent, String name) {
        return new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), Thread.ofVirtual().name(name, 0).factory());
    }

    private static void closeAsync(ExecutorService executor, Closeable resource) {
        try {
            executor.execute(() -> {
                try {
                    resource.close();
                } catch (IOException ignored) {
                    // The scan is already failing closed.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The scanner is already failing closed and the closer pool is bounded.
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        sourceClosers.shutdown();
        socketClosers.shutdown();
    }

    @FunctionalInterface
    public interface ScannerReadiness {
        ScannerStatus current() throws IOException;
    }

    public record ScannerStatus(UUID generation, String configDigest, String snapshotDigest,
                                String engineVersion, String definitionVersion, Instant validUntil,
                                boolean encryptedAlertEnabled, boolean limitAlertEnabled) {
    }

    public record Result(DocumentContentInspector.Result content, String engineVersion, String definitionVersion) {
    }

    public static final class MalwareDetectedException extends IOException {
        public MalwareDetectedException() {
            super("Malware detected");
        }
    }
}
