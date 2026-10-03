package com.aiproxy.cli;

import com.aiproxy.bootstrap.ProxyRuntime;
import com.aiproxy.config.ServerConfig;
import com.aiproxy.util.ApiKeyUtils;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class ProxyCommandTest {

    private static final PrintStream NULL_STREAM = new PrintStream(OutputStream.nullOutputStream());

    @Test
    void testHelp() {
        ProxyCommand app = new ProxyCommand();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(sw));
        
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("AI proxy exposing"), "Output was: " + output);
    }

    @Test
    void testVersion() {
        ProxyCommand app = new ProxyCommand();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(sw));
        
        int exitCode = cmd.execute("--version");
        assertEquals(0, exitCode);
        assertTrue(sw.toString().contains("AIProxy 5.1"));
    }

    @Test
    void testGenerateKey() {
        ProxyCommand app = new ProxyCommand();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(sw));
        
        int exitCode = cmd.execute("key", "generate");
        assertEquals(0, exitCode);
        String output = sw.toString().trim();
        assertTrue(output.startsWith("sk-proxy-"));
        assertEquals(41, output.length());
    }

    @Test
    void testGenerateNamedKey() {
        ProxyCommand app = new ProxyCommand();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("key", "generate", "myapp");
        assertEquals(0, exitCode);
        String output = sw.toString().trim();
        assertTrue(output.startsWith("myapp:sk-proxy-"));
    }

    @Test
    void testFindExistingAuthFile() {
        String result = ProxyRuntime.findExistingAuthFile("test");
        assertNull(result);
    }

    @Test
    void testParseKeyEntry() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        
        // Bare key
        ApiKeyUtils.parseKeyEntry("sk-123", map);
        assertEquals("sk-123", map.get("sk-123"));
        
        // Name:Key
        ApiKeyUtils.parseKeyEntry("myapp:sk-456", map);
        assertEquals("myapp", map.get("sk-456"));
        
        // With whitespace
        ApiKeyUtils.parseKeyEntry("  otherapp :  sk-789  ", map);
        assertEquals("otherapp", map.get("sk-789"));
        
        // Invalid (empty name or key)
        int sizeBefore = map.size();
        ApiKeyUtils.parseKeyEntry(":", map);
        ApiKeyUtils.parseKeyEntry("name:", map);
        ApiKeyUtils.parseKeyEntry(":key", map);
        assertEquals(sizeBefore, map.size());
    }

    @Test
    void testParseModelList() {
        ProxyCommand app = new ProxyCommand();
        CommandLine cmd = new CommandLine(app);
        
        // Use reflection or package-private access to set private fields for testing buildServerConfig
        // But for parseModelList we can test it directly if we set the field
        cmd.parseArgs("--codex-models", "gpt-4, gpt-3.5-turbo , , gpt-4o");
        java.util.List<String> models = app.parseModelList();
        assertEquals(java.util.List.of("gpt-4", "gpt-3.5-turbo", "gpt-4o"), models);
    }

    @Test
    void testBuildServerConfig() throws Exception {
        ProxyCommand app = new ProxyCommand(() -> Map.of("AIPROXY_ADMIN_CLIENT_KEY", "adm"));
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("--host", "0.0.0.0", "--port", "9000", "--codex-models", "m1,m2");
        
        com.aiproxy.config.ServerConfig config = app.buildServerConfig();
        assertEquals("0.0.0.0", config.host());
        assertEquals(9000, config.port());
        assertEquals(java.util.List.of("m1", "m2"), config.models());
        assertEquals("adm", config.adminKey());
    }

    @Test
    void testBuildServerConfigAllowsAnyCorsOnlyWhenFlagProvided() throws Exception {
        ProxyCommand app = new ProxyCommand(() -> Map.of("AIPROXY_CLIENT_KEYS", "sk-proxy-test"));
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("--allow-any-cors");

        com.aiproxy.config.ServerConfig config = app.buildServerConfig();
        assertTrue(config.allowAnyCors());
        assertTrue(config.allowedCorsOrigins().isEmpty());
    }

    @Test
    void testBuildServerConfigParsesCorsOrigins() throws Exception {
        ProxyCommand app = new ProxyCommand();
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("--cors-origin", "https://one.example,https://two.example");

        com.aiproxy.config.ServerConfig config = app.buildServerConfig();
        assertFalse(config.allowAnyCors());
        assertEquals(java.util.List.of("https://one.example", "https://two.example"), config.allowedCorsOrigins());
    }

    @Test
    void testBuildServerConfigParsesRequestLoggingOptions(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        ProxyCommand app = new ProxyCommand();
        CommandLine cmd = new CommandLine(app);
        java.nio.file.Path logDir = tempDir.resolve("request-logs");
        cmd.parseArgs(
                "--log-requests",
                "--request-log-dir", logDir.toString(),
                "--codex-forward-prompt-cache-headers"
        );

        com.aiproxy.config.ServerConfig config = app.buildServerConfig();

        assertTrue(config.fullRequestLogging());
        assertEquals(logDir.toAbsolutePath().normalize().toString(), config.requestLogDir());
        assertTrue(config.forwardPromptCacheHeaders());
    }

    @Test
    void testBuildServerConfigRejectsFullNetworkOpenMode() {
        ProxyCommand app = new ProxyCommand();
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("--host", "0.0.0.0");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, app::buildServerConfig);
        assertTrue(ex.getMessage().contains("API key enforcement is required"));
    }

    @Test
    void testBuildServerConfig_AdminInFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        ProxyCommand app = new ProxyCommand();
        CommandLine cmd = new CommandLine(app);
        
        java.nio.file.Path keysFile = tempDir.resolve("keys.txt");
        java.nio.file.Path adminFile = tempDir.resolve("admin.txt");
        java.nio.file.Files.writeString(keysFile, "user1:sk-user-123\n");
        java.nio.file.Files.writeString(adminFile, "sk-admin-123\n");
        
        cmd.parseArgs("--client-keys-file", keysFile.toString(), "--admin-client-key-file", adminFile.toString());
        
        com.aiproxy.config.ServerConfig config = app.buildServerConfig();
        assertEquals("sk-admin-123", config.adminKey());
        assertEquals(1, config.apiKeys().size());
        assertEquals("user1", config.apiKeys().get("sk-user-123"));
        assertFalse(config.apiKeys().containsKey("sk-admin-123"));
    }

    @Test
    void testResolveAvailableModels() throws Exception {
        ProxyCommand app = new ProxyCommand();
        com.aiproxy.provider.codex.model.CodexModelResolver mockResolver = org.mockito.Mockito.mock(com.aiproxy.provider.codex.model.CodexModelResolver.class);
        
        // Success case
        org.mockito.Mockito.when(mockResolver.resolveModels()).thenReturn(java.util.List.of("gpt-5"));
        java.util.List<String> models = new ProxyRuntime().resolveAvailableModels(mockResolver);
        assertEquals(java.util.List.of("gpt-5"), models);
        
        // Exception case — suppresses expected "Warning: Could not discover models" stderr output
        org.mockito.Mockito.when(mockResolver.resolveModels()).thenThrow(new RuntimeException("fail"));
        PrintStream saved = System.err;
        System.setErr(NULL_STREAM);
        try {
            java.util.List<String> emptyModels = new ProxyRuntime().resolveAvailableModels(mockResolver);
            assertTrue(emptyModels.isEmpty());
        } finally {
            System.setErr(saved);
        }
    }

    @Test
    void testStartupProbePostsChatCompletionThroughProxy() throws Exception {
        ProxyCommand app = new ProxyCommand();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = """
                    data: {"id":"chatcmpl_test","object":"chat.completion.chunk","choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}

                    data: {"id":"chatcmpl_test","object":"chat.completion.chunk","choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}

                    data: {"id":"chatcmpl_test","object":"chat.completion.chunk","choices":[{"delta":{"content":" from proxy"},"finish_reason":null}]}

                    data: [DONE]

                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            int port = server.getAddress().getPort();
            com.aiproxy.config.ServerConfig config = new com.aiproxy.config.ServerConfig(
                    "127.0.0.1", port, null, null, "http://base", null, null, null, "", false, java.util.Map.of(), null
            );

            ProxyRuntime.StartupProbeResult result =
                    new ProxyRuntime().verifyChatCompletionThroughProxy(config, java.util.List.of("gpt-5.2"), null, httpClient);

            assertTrue(result.success(), result.message());
            assertEquals(200, result.statusCode());
            assertEquals("Hello from proxy", result.responseText());
            assertEquals("POST", method.get());
            assertEquals("/v1/chat/completions", path.get());
            assertEquals("application/json", contentType.get());
            assertTrue(body.get().contains("\"model\":\"gpt-5.2\""), "Body was: " + body.get());
            assertTrue(body.get().contains("\"role\":\"user\""), "Body was: " + body.get());
            assertTrue(body.get().contains("\"content\":\"Hello!\""), "Body was: " + body.get());
            assertTrue(body.get().contains("\"stream\":true"), "Body was: " + body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicStartupProbeTreatsWellFormedEmptyContentAsSuccess() throws Exception {
        ProxyCommand app = new ProxyCommand();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                    {"id":"msg_x","type":"message","role":"assistant","content":[],"stop_reason":"max_tokens"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            ServerConfig config = new ServerConfig("127.0.0.1", server.getAddress().getPort(), null,
                    null, "http://base", null, null, null, "", false, Map.of(), null);

            ProxyRuntime.StartupProbeResult result = new ProxyRuntime().verifyAnthropicThroughProxy(
                    config, List.of("claude-sonnet-test"), "sk-proxy-test", client);

            assertTrue(result.success(), result.message());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicStartupProbeUsesNativeMessagesEndpoint() throws Exception {
        ProxyCommand app = new ProxyCommand();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"msg_test","type":"message","role":"assistant","content":[{"type":"text","text":"OK"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            ServerConfig config = new ServerConfig("127.0.0.1", server.getAddress().getPort(), null,
                    null, "http://base", null, null, null, "", false, Map.of(), null);

            ProxyRuntime.StartupProbeResult result = new ProxyRuntime().verifyAnthropicThroughProxy(
                    config, List.of("claude-sonnet-test"), "sk-proxy-test", client);

            assertTrue(result.success(), result.message());
            assertEquals("/v1/messages", path.get());
            assertEquals("sk-proxy-test", key.get());
            assertTrue(body.get().contains("\"model\":\"claude-sonnet-test\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testStartupProbePrefersHaikuOverCatalogFirstOpus() throws Exception {
        ProxyCommand app = new ProxyCommand();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    data: {"choices":[{"delta":{"content":"ready"}}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            ServerConfig config = new ServerConfig(
                    "127.0.0.1", server.getAddress().getPort(), null, null,
                    "http://base", null, null, null, "", false, Map.of(), null);

            ProxyRuntime.StartupProbeResult result = new ProxyRuntime().verifyChatCompletionThroughProxy(
                    config,
                    List.of("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5"),
                    null,
                    httpClient);

            assertTrue(result.success(), result.message());
            assertEquals("claude-haiku-4-5", result.model());
            assertTrue(body.get().contains("\"model\":\"claude-haiku-4-5\""), body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startupProbeFallsBackToNextModelWhenFirstIsNotUsable() throws Exception {
        ProxyCommand app = new ProxyCommand();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (req.contains("\"model\":\"copilot/allowed\"")) {
                byte[] ok = """
                        data: {"choices":[{"delta":{"content":"ready"}}]}

                        data: [DONE]

                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
            } else {
                byte[] bad = "{\"error\":{\"message\":\"model not available for integrator\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, bad.length);
                exchange.getResponseBody().write(bad);
            }
            exchange.close();
        });
        server.start();
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            ServerConfig config = new ServerConfig("127.0.0.1", server.getAddress().getPort(), null,
                    null, "http://base", null, null, null, "", false, Map.of(), null);

            ProxyRuntime.StartupProbeResult result = new ProxyRuntime().verifyChatCompletionThroughProxy(
                    config, List.of("copilot/allowed", "copilot/denied"), null, httpClient);

            assertTrue(result.success(), result.message());
            assertEquals("copilot/allowed", result.model());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testStartupProbeReportsNullAssistantContent() throws Exception {
        ProxyCommand app = new ProxyCommand();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = """
                    data: {"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            int port = server.getAddress().getPort();
            com.aiproxy.config.ServerConfig config = new com.aiproxy.config.ServerConfig(
                    "127.0.0.1", port, null, null, "http://base", null, null, null, "", false, java.util.Map.of(), null
            );

            ProxyRuntime.StartupProbeResult result =
                    new ProxyRuntime().verifyChatCompletionThroughProxy(config, java.util.List.of(), null, httpClient);

            assertFalse(result.success());
            assertEquals("<missing streaming choices[].delta.content>", result.responseText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testStartupProbeReportsMissingAssistantContent() throws Exception {
        ProxyCommand app = new ProxyCommand();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = """
                    data: {"choices":[]}

                    data: [DONE]

                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            int port = server.getAddress().getPort();
            com.aiproxy.config.ServerConfig config = new com.aiproxy.config.ServerConfig(
                    "127.0.0.1", port, null, null, "http://base", null, null, null, "", false, java.util.Map.of(), null
            );

            ProxyRuntime.StartupProbeResult result =
                    new ProxyRuntime().verifyChatCompletionThroughProxy(config, java.util.List.of(), null, httpClient);

            assertFalse(result.success());
            assertEquals("<missing streaming choices[].delta.content>", result.responseText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testStartupProbeUsesApiKeyWhenProvided() throws Exception {
        ProxyCommand app = new ProxyCommand();
        AtomicReference<String> authorization = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    data: {"choices":[{"delta":{"content":"Authenticated response"},"finish_reason":null}]}

                    data: [DONE]

                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            int port = server.getAddress().getPort();
            com.aiproxy.config.ServerConfig config = new com.aiproxy.config.ServerConfig(
                    "127.0.0.1", port, java.util.List.of("gpt-5.2"), null, "http://base", null, null, null, "", false, java.util.Map.of(), null
            );

            ProxyRuntime.StartupProbeResult result =
                    new ProxyRuntime().verifyChatCompletionThroughProxy(config, java.util.List.of(), "sk-proxy-test", httpClient);

            assertTrue(result.success(), result.message());
            assertEquals("Bearer sk-proxy-test", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testStartupProbeReportsNonSuccessStatus() throws Exception {
        ProxyCommand app = new ProxyCommand();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"error\":\"bad\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            int port = server.getAddress().getPort();
            com.aiproxy.config.ServerConfig config = new com.aiproxy.config.ServerConfig(
                    "127.0.0.1", port, null, null, "http://base", null, null, null, "", false, java.util.Map.of(), null
            );

            ProxyRuntime.StartupProbeResult result =
                    new ProxyRuntime().verifyChatCompletionThroughProxy(config, java.util.List.of(), null, httpClient);

            assertFalse(result.success());
            assertEquals(502, result.statusCode());
            assertTrue(result.message().contains("502"), result.message());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testParseApiKeyMap(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        ProxyCommand app = new ProxyCommand(() -> Map.of("AIPROXY_CLIENT_KEYS", "inlinekey1,app1:inlinekey2"));
        CommandLine cmd = new CommandLine(app);
        
        // Create a temp keys file
        java.nio.file.Path keysFile = tempDir.resolve("keys.txt");
        java.nio.file.Files.writeString(keysFile, "# Comment line\nfilekey1\napp2:filekey2\n");
        
        cmd.parseArgs("--client-keys-file", keysFile.toString());
        
        java.util.Map<String, String> keyMap = app.parseApiKeyMap();
        
        assertEquals(4, keyMap.size());
        assertEquals("inlinekey1", keyMap.get("inlinekey1"));
        assertEquals("app1", keyMap.get("inlinekey2"));
        assertEquals("filekey1", keyMap.get("filekey1"));
        assertEquals("app2", keyMap.get("filekey2"));
    }

    @Test
    void testCheckAuthFileExists_Failure() {
        ProxyCommand app = new ProxyCommand();
        com.aiproxy.config.ServerConfig config = new com.aiproxy.config.ServerConfig(
                "127.0.0.1", 10531, null, null, "http://base", null, null, "/non/existent/path", "", false, java.util.Map.of(), null
        );
        // Suppresses expected "No auth file was found" stderr output
        PrintStream saved = System.err;
        System.setErr(NULL_STREAM);
        try {
            assertFalse(new ProxyRuntime().checkAuthFileExists(config));
        } finally {
            System.setErr(saved);
        }
    }
}
