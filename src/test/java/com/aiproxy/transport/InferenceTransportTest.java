package com.aiproxy.transport;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InferenceTransportTest {
    @Test void bodyReadsUseSharedDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(422, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
            var response = InferenceTransport.send(client, request, Duration.ofMillis(100));
            assertEquals(422, response.statusCode());
            try (var body = response.body()) {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(HttpTimeoutException.class, body::readAllBytes));
            }
        } finally { release.countDown(); server.stop(0); }
    }
}
