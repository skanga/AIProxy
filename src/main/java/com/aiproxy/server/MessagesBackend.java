package com.aiproxy.server;

import io.javalin.http.Context;
import tools.jackson.databind.node.ObjectNode;

/** Executes a validated native Messages request; never participates in inference failover. */
@FunctionalInterface
public interface MessagesBackend {
    void handle(Context context, ObjectNode body) throws Exception;
}
