package com.aiproxy.protocol.messages;

import com.aiproxy.provider.spi.ProviderError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MessagesErrorParserTest {
    @Test
    void mapsKnownAnthropicErrorTypes() {
        ProviderError error = MessagesErrorParser.parse(429, """
                {"type":"error","error":{"type":"rate_limit_error","message":"Slow down"}}
                """);

        assertEquals(ProviderError.Kind.RATE_LIMIT, error.kind());
        assertEquals(429, error.httpStatus());
        assertEquals("Slow down", error.message());
    }

    @Test
    void malformedErrorsAreGenericAndNeverEchoBody() {
        String secret = "secret-body-marker";
        ProviderError error = MessagesErrorParser.parse(502, "malformed-" + secret);

        assertEquals(ProviderError.Kind.PROTOCOL, error.kind());
        assertFalse(error.message().contains(secret));
    }

    @Test
    void authenticationErrorsDoNotEchoPotentialCredentials() {
        String secret = "Bearer credential-that-must-not-leak";
        ProviderError error = MessagesErrorParser.parse(401, """
                {"type":"error","error":{"type":"authentication_error","message":"%s"}}
                """.formatted(secret));

        assertEquals(ProviderError.Kind.AUTHENTICATION, error.kind());
        assertFalse(error.message().contains(secret));
    }
}
