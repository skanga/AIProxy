package com.aiproxy.protocol.messages;

import com.aiproxy.provider.spi.ProviderError;
import java.util.Objects;

public final class MessagesEncodingException extends Exception {
    private final ProviderError error;

    public MessagesEncodingException(String message) {
        super(message);
        this.error = ProviderError.of(
                ProviderError.Kind.INVALID_REQUEST,
                Objects.requireNonNull(message, "message")
        );
    }

    public ProviderError error() {
        return error;
    }
}
