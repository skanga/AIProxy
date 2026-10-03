package com.aiproxy.server;

import io.javalin.http.Handler;
import java.util.Objects;

/** Fully assembled endpoint handlers supplied by application bootstrap. */
public record ProxyEndpoints(Handler models, Handler chat, Handler responses, Handler messages) {
    public ProxyEndpoints {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(responses, "responses");
        Objects.requireNonNull(messages, "messages");
    }
}
