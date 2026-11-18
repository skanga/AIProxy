package com.aiproxy.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounds each blocking upstream read, not total response duration or downstream write time. */
public final class ReadIdleTimeoutInputStream extends InputStream {
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();
    private final InputStream input;
    private final long timeoutNanos;
    private final Object lock = new Object();
    private ScheduledFuture<?> alarm;
    private long generation;
    private boolean timedOut;
    private boolean closed;

    public ReadIdleTimeoutInputStream(InputStream input, Duration timeout) {
        this.input = Objects.requireNonNull(input);
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0) throw new IllegalArgumentException("Read timeout must be positive");
    }

    private static ScheduledThreadPoolExecutor watchdog() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "upstream-read-timeout");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        synchronized (lock) {
            if (timedOut) throw timeout(null);
            if (closed) throw new IOException("Stream is closed");
            long current = ++generation;
            alarm = WATCHDOG.schedule(() -> expire(current), timeoutNanos, TimeUnit.NANOSECONDS);
        }
        int count;
        try {
            count = input.read(bytes, offset, length);
        } catch (IOException error) {
            synchronized (lock) {
                if (timedOut) throw timeout(error);
            }
            throw error;
        } finally {
            synchronized (lock) {
                ++generation;
                alarm.cancel(false);
                alarm = null;
            }
        }
        synchronized (lock) {
            // Closing a timed-out HTTP body may return EOF rather than throw.
            if (timedOut) throw timeout(null);
        }
        return count;
    }

    private void expire(long current) {
        synchronized (lock) {
            if (closed || generation != current) return;
            timedOut = true;
        }
        // Never let a slow close block deadlines for other requests.
        Thread.startVirtualThread(() -> {
            try { input.close(); } catch (IOException ignored) { }
        });
    }

    private static HttpTimeoutException timeout(IOException cause) {
        var error = new HttpTimeoutException("Upstream response read timed out");
        if (cause != null) error.initCause(cause);
        return error;
    }

    @Override public void close() throws IOException {
        synchronized (lock) {
            closed = true;
            ++generation;
            if (alarm != null) alarm.cancel(false);
        }
        input.close();
    }
}
