package com.aiproxy.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiproxy.config.ConfigOverrides;
import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.usage.UsageTracker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class OperationalLoggingTest {
    @Test
    void requestLogsUseSlf4jAndShareRequestIdWithoutLeakingToNextRequest() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> capture = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        capture.start();
        root.addAppender(capture);
        var config = EffectiveConfigLoader.load(null, Map.of(), new ConfigOverrides());
        io.javalin.http.Handler failureHandler = ctx -> { throw new IllegalStateException("test failure"); };
        var server = new ProxyServer(config.legacyServerConfig(Map.of(), null),
                new ProxyEndpoints(failureHandler, failureHandler, failureHandler, failureHandler),
                new UsageTracker(), new ApiKeyStore(Map.of(), null, null),
                new com.aiproxy.logging.RequestLogger(false, java.nio.file.Path.of("logs")));
        server.getApp().start(0);
        try {
            var http = HttpClient.newHttpClient();
            for (String path : List.of("/v1/models", "/health?token=private-secret")) {
                var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + server.getApp().port() + path)).header("Authorization", "Bearer private-secret")
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(path.startsWith("/v1/models") ? 500 : 200, response.statusCode());
            }
        } finally {
            server.stop();
            root.detachAppender(capture);
            capture.stop();
        }
        var access = capture.list.stream().filter(e -> e.getLoggerName().equals("com.aiproxy.access")).toList();
        assertEquals(2, access.size());
        String firstId = access.getFirst().getMDCPropertyMap().get("request_id");
        assertNotNull(firstId);
        assertTrue(firstId.startsWith("aiproxy_"));
        assertNotEquals(firstId, access.getLast().getMDCPropertyMap().get("request_id"));
        var failure = capture.list.stream().filter(e -> e.getLevel() == Level.ERROR
                && e.getLoggerName().equals(ProxyServer.class.getName())).findFirst().orElseThrow();
        assertEquals(firstId, failure.getMDCPropertyMap().get("request_id"));
        assertNotNull(access.getFirst().getKeyValuePairs());
        assertFalse(access.toString().contains("private-secret"));
        assertFalse(access.toString().contains("Authorization"));
        assertNull(org.slf4j.MDC.get("request_id"));
    }
}
