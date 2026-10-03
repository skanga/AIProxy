package com.aiproxy.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBodyReaderTest {
    @Test
    void errorReaderAcceptsExactLimitAndUsesTheSuppliedOverflowFallback() throws Exception {
        assertEquals("é", BoundedBodyReader.readError(bytes("é"), 2, "{}"));
        assertEquals("{}", BoundedBodyReader.readError(bytes("abc"), 2, "{}"));
        assertEquals("", BoundedBodyReader.readError(bytes("abc"), 2, ""));
    }

    @Test
    void errorReaderBoundsConsumptionAndLeavesStreamOwnershipWithCaller() throws Exception {
        var input = new ByteArrayInputStream(new byte[100]) {
            @Override public void close() { fail("Reader must not close the upstream stream"); }
        };
        assertEquals("overflow", BoundedBodyReader.readError(input, 4, "overflow"));
        assertEquals(95, input.available());
        assertThrows(IOException.class, () -> BoundedBodyReader.readError(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("reset"); }
        }, 4, "overflow"));
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
