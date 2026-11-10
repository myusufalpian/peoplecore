package id.mydev.peoplecore.document.infrastructure.format;

import id.mydev.peoplecore.document.application.service.DocumentContentInspector;
import id.mydev.peoplecore.document.application.service.DocumentFormatVerifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class IsolatedDocumentFormatVerifier implements DocumentFormatVerifier, AutoCloseable {
    private final Path parserJar;
    private final Duration timeout;
    private final long maxHeapBytes;
    private final List<String> sandboxCommand;
    private final Semaphore permits;
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService closers;
    private final Set<Execution> executions = ConcurrentHashMap.newKeySet();

    public IsolatedDocumentFormatVerifier(Path parserJar, Duration timeout, int maxConcurrent,
                                          long maxHeapBytes, List<String> sandboxCommand) {
        this.parserJar = Objects.requireNonNull(parserJar, "parserJar").toAbsolutePath();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.sandboxCommand = List.copyOf(sandboxCommand);
        if (!Files.isRegularFile(this.parserJar) || timeout.isNegative() || timeout.isZero()
            || timeout.toMillis() < 1 || maxConcurrent < 1 || maxHeapBytes < 1) {
            throw new IllegalArgumentException("Invalid isolated parser configuration");
        }
        this.maxHeapBytes = maxHeapBytes;
        permits = new Semaphore(maxConcurrent);
        closers = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), Thread.ofVirtual().name("document-parser-close-", 0).factory());
    }

    @Override
    public void verify(InputStream source, String mediaType, long byteSize, String expectedSha256) throws IOException {
        Objects.requireNonNull(source, "source");
        if (mediaType == null || !Set.of("application/pdf", "image/jpeg", "image/png").contains(mediaType)
            || byteSize < 1 || byteSize > DocumentContentInspector.MAX_BYTES
            || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new DocumentContentInspector.InvalidDocumentContentException("Invalid document declaration");
        }
        if (!permits.tryAcquire()) {
            throw new IOException("Document parser capacity is unavailable");
        }
        Execution execution = new Execution(source);
        executions.add(execution);
        Future<?> task;
        try {
            task = tasks.submit(() -> {
                try {
                    parse(execution, mediaType, byteSize, expectedSha256);
                } finally {
                    executions.remove(execution);
                    permits.release();
                }
                return null;
            });
        } catch (RejectedExecutionException exception) {
            executions.remove(execution);
            permits.release();
            throw new IOException("Document parser is unavailable", exception);
        }
        try {
            task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            cancel(execution);
            throw new IOException("Document parser timed out", exception);
        } catch (InterruptedException exception) {
            cancel(execution);
            Thread.currentThread().interrupt();
            throw new IOException("Document parsing was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException failure) {
                throw failure;
            }
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IOException("Document parser failed", cause);
        }
    }

    public static Path extractBundledParser(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        try (InputStream bundled = IsolatedDocumentFormatVerifier.class.getResourceAsStream("/document-parser/document-parser.jar")) {
            if (bundled == null) {
                throw new IOException("Bundled document parser is unavailable");
            }
            Path extracted = Files.createTempFile(directory, "document-parser-", ".jar");
            try {
                Files.copy(bundled, extracted, StandardCopyOption.REPLACE_EXISTING);
                return extracted;
            } catch (IOException exception) {
                Files.deleteIfExists(extracted);
                throw exception;
            }
        }
    }

    private void parse(Execution execution, String mediaType, long byteSize, String expectedSha256) throws IOException {
        List<String> command = new ArrayList<>(sandboxCommand);
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx" + maxHeapBytes);
        command.add("-jar");
        command.add(parserJar.toString());
        command.add(mediaType);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        Process process = builder.start();
        execution.process.set(process);
        if (execution.cancelled.get()) {
            process.destroyForcibly();
            throw new IOException("Document parsing was cancelled");
        }
        try {
            String actualSha = copyInput(execution.source, process.getOutputStream(), byteSize);
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(expectedSha256), HexFormat.of().parseHex(actualSha))) {
                throw new IOException("Document candidate changed during verification");
            }
            int exit = process.waitFor();
            if (exit == 2) {
                throw new DocumentContentInspector.InvalidDocumentContentException("Invalid document format");
            }
            if (exit != 0) {
                throw new IOException("Document parser failed");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Document parsing was interrupted", exception);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String copyInput(InputStream source, OutputStream processInput, long expectedSize) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        long total = 0;
        byte[] buffer = new byte[8192];
        try (processInput) {
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (count > expectedSize - total) {
                    throw new IOException("Document candidate changed during verification");
                }
                digest.update(buffer, 0, count);
                processInput.write(buffer, 0, count);
                total += count;
            }
        }
        if (total != expectedSize) {
            throw new IOException("Document candidate changed during verification");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void cancel(Execution execution) {
        execution.cancelled.set(true);
        Process process = execution.process.get();
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        try {
            closers.execute(() -> {
                try {
                    execution.source.close();
                } catch (IOException ignored) {
                    // Verification is already cancelled.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Bounded capacity prevents additional work until the source returns.
        }
    }

    @Override
    public void close() {
        executions.forEach(this::cancel);
        tasks.shutdown();
        closers.shutdown();
    }

    private static final class Execution {
        private final InputStream source;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Process> process = new AtomicReference<>();

        private Execution(InputStream source) {
            this.source = source;
        }
    }
}
