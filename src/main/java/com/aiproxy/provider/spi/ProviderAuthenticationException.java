package com.aiproxy.provider.spi;

/** A provider credential failure whose message is safe to expose to the client. */
public class ProviderAuthenticationException extends RuntimeException {
    public ProviderAuthenticationException(String message) {
        super(message);
    }
}
