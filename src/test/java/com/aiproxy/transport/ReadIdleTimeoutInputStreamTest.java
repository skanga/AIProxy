package com.aiproxy.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadIdleTimeoutInputStreamTest {
    @Test
    void timeoutClosesBlockedReadEvenWhenCloseReturnsEof() {
        var closed = new CountDownLatch(1);
        InputStream blocked = new InputStream() {
            @Override public int read() throws IOException {
                try {
                    if (!closed.await(3, TimeUnit.SECONDS)) throw new IOException("Not closed");
                    return -1;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
            }
            @Override public void close() { closed.countDown(); }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (var input = new ReadIdleTimeoutInputStream(blocked, Duration.ofMillis(100))) {
                assertThrows(HttpTimeoutException.class, input::readAllBytes);
                assertEquals(0, closed.getCount());
                assertThrows(HttpTimeoutException.class, input::read);
            }
        });
    }

    @Test
    void progressResetsDeadlineRatherThanLimitingTotalBodyDuration() throws Exception {
        InputStream progressing = new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                try { Thread.sleep(75); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        try (var input = new ReadIdleTimeoutInputStream(progressing, Duration.ofMillis(300))) {
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, input.readAllBytes());
        }
    }

    @Test
    void timeBetweenReadsDoesNotExpireOrLeaveCancelledTimersRunning() throws Exception {
        InputStream underlying = spy(new ByteArrayInputStream(new byte[]{1, 2}));
        try (var input = new ReadIdleTimeoutInputStream(underlying, Duration.ofMillis(50))) {
            assertEquals(1, input.read());
            Thread.sleep(150);
            verify(underlying, never()).close();
            assertEquals(2, input.read());
            assertEquals(-1, input.read());
        }
        Thread.sleep(150);
        verify(underlying, times(1)).close();
    }

    @Test
    void ordinaryIoFailuresAreNotRelabeledAsTimeouts() throws Exception {
        var failure = new IOException("connection reset");
        InputStream underlying = mock(InputStream.class);
        when(underlying.read(any(byte[].class), anyInt(), anyInt())).thenThrow(failure);
        try (var input = new ReadIdleTimeoutInputStream(underlying, Duration.ofSeconds(1))) {
            assertSame(failure, assertThrows(IOException.class, input::read));
        }
    }

    @Test
    void rejectsNonpositiveTimeouts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReadIdleTimeoutInputStream(InputStream.nullInputStream(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadIdleTimeoutInputStream(InputStream.nullInputStream(), Duration.ofSeconds(-1)));
    }
}
