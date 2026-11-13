package id.mydev.peoplecore.document.application.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.io.Closeable;

public final class RevocableDocumentTransfer implements AutoCloseable {
    private static final Duration MAX_RECHECK_DELAY = Duration.ofMinutes(5);
    private final Duration pollInterval;
    private final Duration checkTimeout;
    private final Semaphore permits;
    private final ExecutorService checks;
    private final ExecutorService closers;
    private final ScheduledExecutorService watchers;
    private final Set<Transfer> transfers = ConcurrentHashMap.newKeySet();

    public RevocableDocumentTransfer(int maxConcurrent, Duration pollInterval, Duration checkTimeout) {
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.checkTimeout = Objects.requireNonNull(checkTimeout, "checkTimeout");
        if (maxConcurrent < 1 || pollInterval.isNegative() || pollInterval.isZero() || pollInterval.toMillis() < 1
            || checkTimeout.isNegative() || checkTimeout.isZero() || checkTimeout.toMillis() < 1
            || pollInterval.compareTo(MAX_RECHECK_DELAY) > 0
            || checkTimeout.compareTo(MAX_RECHECK_DELAY.minus(pollInterval).dividedBy(2)) > 0) {
            throw new IllegalArgumentException("Invalid transfer concurrency or authorization timing");
        }
        permits = new Semaphore(maxConcurrent);
        checks = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(maxConcurrent), Thread.ofVirtual().name("document-access-", 0).factory());
        closers = new ThreadPoolExecutor(Math.multiplyExact(maxConcurrent, 2), Math.multiplyExact(maxConcurrent, 2),
            0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), Thread.ofVirtual().name("document-close-", 0).factory());
        watchers = Executors.newScheduledThreadPool(maxConcurrent, Thread.ofVirtual().name("document-watch-", 0).factory());
    }

    public void transfer(InputStream source, OutputStream destination, long byteSize, AccessCheck check) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(check, "check");
        if (byteSize < 1 || byteSize > DocumentContentInspector.MAX_BYTES) {
            throw new IllegalArgumentException("Invalid document size");
        }
        if (!permits.tryAcquire()) {
            throw new IOException("Document transfer capacity is unavailable");
        }
        Transfer transfer = new Transfer(source, destination);
        transfers.add(transfer);
        ScheduledFuture<?> watcher = null;
        try {
            requireAccess(transfer, check);
            watcher = watchers.scheduleWithFixedDelay(() -> watch(transfer, check),
                pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
            copy(transfer, byteSize);
            requireAccess(transfer, check);
            if (!transfer.finish()) {
                throw new IOException("Document transfer authorization ended");
            }
        } catch (RejectedExecutionException exception) {
            transfer.abort();
            throw new IOException("Document transfer is unavailable", exception);
        } catch (IOException | RuntimeException exception) {
            transfer.abort();
            throw exception;
        } finally {
            if (watcher != null) {
                watcher.cancel(true);
            }
            transfers.remove(transfer);
            permits.release();
        }
    }

    private void watch(Transfer transfer, AccessCheck check) {
        if (!transfer.active()) {
            return;
        }
        try {
            requireAccess(transfer, check);
        } catch (IOException exception) {
            transfer.abort();
        }
    }

    private void requireAccess(Transfer transfer, AccessCheck check) throws IOException {
        synchronized (transfer.accessLock) {
            if (!transfer.active()) {
                throw new IOException("Document transfer authorization ended");
            }
            performAccessCheck(check);
        }
    }

    private void performAccessCheck(AccessCheck check) throws IOException {
        Future<Boolean> result;
        try {
            result = checks.submit(check::allowed);
        } catch (RejectedExecutionException exception) {
            throw new IOException("Document authorization is unavailable", exception);
        }
        try {
            if (!Boolean.TRUE.equals(result.get(checkTimeout.toMillis(), TimeUnit.MILLISECONDS))) {
                throw new IOException("Document transfer authorization ended");
            }
        } catch (InterruptedException exception) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Document authorization was interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            result.cancel(true);
            throw new IOException("Document authorization is unavailable", exception);
        }
    }

    private static void copy(Transfer transfer, long expectedSize) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = transfer.source.read(buffer)) != -1) {
            if (count > expectedSize - total) {
                throw new IOException("Document content exceeds its verified size");
            }
            synchronized (transfer.writeLock) {
                if (!transfer.active()) {
                    throw new IOException("Document transfer authorization ended");
                }
                transfer.destination.write(buffer, 0, count);
            }
            total += count;
        }
        if (total != expectedSize) {
            throw new IOException("Document content does not match its verified size");
        }
    }

    @Override
    public void close() {
        for (Transfer transfer : transfers) {
            transfer.abort();
        }
        watchers.shutdownNow();
        checks.shutdownNow();
        closers.shutdown();
    }

    @FunctionalInterface
    public interface AccessCheck {
        boolean allowed() throws Exception;
    }

    private enum State { ACTIVE, FINISHED, ABORTED }

    private final class Transfer {
        private final InputStream source;
        private final OutputStream destination;
        private final Object accessLock = new Object();
        private final Object writeLock = new Object();
        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

        private Transfer(InputStream source, OutputStream destination) {
            this.source = source;
            this.destination = destination;
        }

        private boolean active() {
            return state.get() == State.ACTIVE;
        }

        private boolean finish() {
            return state.compareAndSet(State.ACTIVE, State.FINISHED);
        }

        private void abort() {
            if (!state.compareAndSet(State.ACTIVE, State.ABORTED)) {
                return;
            }
            closeAsync(source);
            closeAsync(destination);
        }
    }

    private void closeAsync(Closeable resource) {
        try {
            closers.execute(() -> {
                try {
                    resource.close();
                } catch (IOException ignored) {
                    // Access has already ended.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The transfer is already aborted; transport timeouts remain necessary.
        }
    }
}
