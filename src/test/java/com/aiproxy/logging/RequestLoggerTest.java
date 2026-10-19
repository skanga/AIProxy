package com.aiproxy.logging;

import com.aiproxy.util.Json;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestLoggerTest {

    @Test
    void errorResponsesRedactStructuredSecretsAndBoundBodies(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        ErrorLogger logger = new ErrorLogger(tempDir.resolve("errors.jsonl"));
        Context ctx = mock(Context.class);
        when(ctx.method()).thenReturn(HandlerType.POST);
        when(ctx.path()).thenReturn("/v1/responses");
        when(ctx.statusCode()).thenReturn(502);
        when(ctx.result()).thenReturn("{\"access_token\":\"secret\",\"error\":\"" + "x".repeat(300 * 1024) + "\"}");

        when(ctx.body()).thenReturn("{\"refresh_token\":\"request-secret\",\"input\":\"" + "y".repeat(300 * 1024) + "\"}");
        when(ctx.headerMap()).thenReturn(Map.of("Authorization", "Bearer header-secret", "Content-Type", "application/json"));

        logger.logErrorResponse("aiproxy_test", ctx);

        JsonNode entry = Json.MAPPER.readTree(Files.readAllLines(tempDir.resolve("errors.jsonl")).getFirst());
        assertEquals("error_response", entry.path("stage").asString());
        assertEquals(502, entry.path("status").asInt());
        assertTrue(entry.path("truncated").asBoolean());
        assertFalse(entry.path("body").asString().contains("secret"));
        assertTrue(entry.path("body").asString().contains("[REDACTED]"));
        assertTrue(entry.path("body").asString().length() <= 256 * 1024);
        JsonNode request = entry.path("request");
        assertTrue(request.path("truncated").asBoolean());
        assertTrue(request.path("body").asString().length() <= 256 * 1024);
        assertTrue(request.path("body").asString().contains("[REDACTED]"));
        assertFalse(request.path("body").asString().contains("request-secret"));
        assertEquals("[REDACTED]", request.path("headers").path("Authorization").asString());
        assertEquals("application/json", request.path("headers").path("Content-Type").asString());
    }

    @Test
    void unavailableRequestBodyDoesNotPreventErrorLog(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Context ctx = mock(Context.class);
        when(ctx.method()).thenReturn(HandlerType.POST);
        when(ctx.statusCode()).thenReturn(413);
        when(ctx.body()).thenThrow(new IllegalStateException("Request too large"));
        when(ctx.result()).thenReturn("Request too large");

        assertDoesNotThrow(() -> new ErrorLogger(tempDir.resolve("errors.jsonl")).logErrorResponse("test", ctx));

        JsonNode entry = Json.MAPPER.readTree(Files.readAllLines(tempDir.resolve("errors.jsonl")).getFirst());
        assertTrue(entry.path("request").path("body_unavailable").asBoolean());
        assertEquals("Request too large", entry.path("body").asString());
    }

    @Test
    void errorLoggingWriteFailureDoesNotFailRequest(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("not-a-directory");
        Files.writeString(file, "occupied");
        Context ctx = mock(Context.class);
        when(ctx.statusCode()).thenReturn(500);
        when(ctx.method()).thenReturn(HandlerType.GET);
        assertDoesNotThrow(() -> new ErrorLogger(file.resolve("errors.jsonl")).logErrorResponse("test", ctx));
    }

    @Test
    void disabledLoggerDoesNotCreateLogFiles(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(false, tempDir.resolve("logs"));

        logger.logUpstreamRequest("req_1", "POST", "/responses", Map.of("Content-Type", "application/json"), "{}");

        assertFalse(Files.exists(tempDir.resolve("logs")));
    }

    @Test
    void createsLogDirectoryAndWritesInboundJson(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path logDir = tempDir.resolve("nested").resolve("requests");
        RequestLogger logger = new RequestLogger(true, logDir);
        Context ctx = mock(Context.class);
        when(ctx.method()).thenReturn(HandlerType.POST);
        when(ctx.path()).thenReturn("/v1/chat/completions");
        when(ctx.statusCode()).thenReturn(202);
        when(ctx.headerMap()).thenReturn(Map.of("User-Agent", "JUnit", "Content-Type", "application/json"));

        logger.logInbound("req_123", ctx, "{\"input\":\"hello\"}");

        JsonNode entry = readOnlyJsonFile(logDir);
        assertEquals("req_123", entry.path("request_id").asString());
        assertFalse(entry.path("timestamp").asString().isBlank());
        assertEquals("inbound", entry.path("stage").asString());
        assertEquals("POST", entry.path("method").asString());
        assertEquals("/v1/chat/completions", entry.path("path").asString());
        assertEquals(202, entry.path("status").asInt());
        assertEquals("JUnit", entry.path("headers").path("User-Agent").asString());
        assertEquals("{\"input\":\"hello\"}", entry.path("body").asString());
        assertFalse(entry.path("truncated").asBoolean());
    }

    @Test
    void redactsSensitiveHeadersCaseInsensitively(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(true, tempDir);

        logger.logUpstreamRequest("req_1", "POST", "/responses", Map.of(
                "authorization", "Bearer token",
                "Proxy-Authorization", "Bearer proxy",
                "X-Api-Key", "sk-test",
                "OpenAI-Api-Key", "sk-openai",
                "Cookie", "session=secret",
                "X-Session-Token", "abc",
                "X-Client-Secret", "def",
                "Content-Type", "application/json",
                "Accept", "application/json",
                "User-Agent", "JUnit"
        ), "{}");

        JsonNode headers = readOnlyJsonFile(tempDir).path("headers");
        assertEquals("[REDACTED]", headers.path("authorization").asString());
        assertEquals("[REDACTED]", headers.path("Proxy-Authorization").asString());
        assertEquals("[REDACTED]", headers.path("X-Api-Key").asString());
        assertEquals("[REDACTED]", headers.path("OpenAI-Api-Key").asString());
        assertEquals("[REDACTED]", headers.path("Cookie").asString());
        assertEquals("[REDACTED]", headers.path("X-Session-Token").asString());
        assertEquals("[REDACTED]", headers.path("X-Client-Secret").asString());
        assertEquals("application/json", headers.path("Content-Type").asString());
        assertEquals("application/json", headers.path("Accept").asString());
        assertEquals("JUnit", headers.path("User-Agent").asString());
    }

    @Test
    void writesResponseStatusAndListHeaders(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(true, tempDir);

        logger.logUpstreamResponse("req_1", 429, Map.of(
                "Set-Cookie", List.of("a=b", "c=d"),
                "Content-Type", List.of("application/json")
        ), "{\"error\":\"rate limited\"}");

        JsonNode entry = readOnlyJsonFile(tempDir);
        assertEquals("upstream_response", entry.path("stage").asString());
        assertEquals(429, entry.path("status").asInt());
        assertEquals("[REDACTED]", entry.path("headers").path("Set-Cookie").get(0).asString());
        assertEquals("[REDACTED]", entry.path("headers").path("Set-Cookie").get(1).asString());
        assertEquals("application/json", entry.path("headers").path("Content-Type").get(0).asString());
    }

    @Test
    void capsBodyAndMarksTruncated(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(true, tempDir);
        String body = "x".repeat(300 * 1024);

        logger.logUpstreamRequest("req_1", "POST", "/responses", Map.of(), body);

        JsonNode entry = readOnlyJsonFile(tempDir);
        assertTrue(entry.path("truncated").asBoolean());
        assertTrue(entry.path("body").asString().length() < body.length());
    }

    @Test
    void recursivelyRedactsOauthTokensCodesAndReasoningSignatures(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(true, tempDir);
        String body = """
                {"access_token":"access-secret","nested":[
                  {"refresh_token":"refresh-secret","code_verifier":"verifier-secret"},
                  {"code":"oauth-code","reasoning_signature":"signature-secret"},
                  {"type":"redacted_thinking","data":{"opaque":"thinking-secret"}},
                  {"redacted_data":{"opaque":"responses-thinking-secret"}}
                ],"prompt":"preserved"}
                """;

        logger.logUpstreamRequest("req_oauth", "POST", "/v1/messages", Map.of(), body);

        JsonNode loggedBody = Json.MAPPER.readTree(readOnlyJsonFile(tempDir).path("body").asString());
        assertEquals("[REDACTED]", loggedBody.path("access_token").asString());
        assertEquals("[REDACTED]", loggedBody.path("nested").get(0).path("refresh_token").asString());
        assertEquals("[REDACTED]", loggedBody.path("nested").get(0).path("code_verifier").asString());
        assertEquals("[REDACTED]", loggedBody.path("nested").get(1).path("code").asString());
        assertEquals(
                "[REDACTED]",
                loggedBody.path("nested").get(1).path("reasoning_signature").asString()
        );
        assertEquals(
                "[REDACTED]",
                loggedBody.path("nested").get(2).path("data").asString()
        );
        assertEquals(
                "[REDACTED]",
                loggedBody.path("nested").get(3).path("redacted_data").asString()
        );
        assertEquals("preserved", loggedBody.path("prompt").asString());
    }

    @Test
    void suppressesEntireMalformedBodyOnOauthPath(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(true, tempDir);

        logger.logUpstreamRequest(
                "req_oauth",
                "POST",
                "/v1/oauth/token",
                Map.of(),
                "refresh_token=secret-that-must-not-appear"
        );

        assertEquals("[REDACTED]", readOnlyJsonFile(tempDir).path("body").asString());
    }

    private static JsonNode readOnlyJsonFile(Path logDir) throws Exception {
        try (var files = Files.list(logDir)) {
            List<Path> jsonFiles = files.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
            assertEquals(1, jsonFiles.size());
            return Json.MAPPER.readTree(Files.readString(jsonFiles.getFirst()));
        }
    }
}
