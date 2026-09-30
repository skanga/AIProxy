package com.aiproxyoauth;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicCredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DoctorRegressionTest {
    @TempDir Path temporary;

    private Map<String, String> environment(int port) {
        return new HashMap<>(Map.of("AIPROXY_PROVIDER", "anthropic", "AIPROXY_PORT", "" + port,
                "AIPROXY_ANTHROPIC_MODELS", "claude-test", "CLAUDE_CODE_OAUTH_TOKEN", "test-token",
                "AIPROXY_ANTHROPIC_OAUTH_FILE", temporary.resolve("auth.json").toString()));
    }

    @Test void credentialsDoctorDoesNotBindAnOccupiedServingPort() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var root = new AIProxyOauth(() -> environment(occupied.getLocalPort()));
            var command = AIProxyOauth.commandLine(root);
            StringWriter errors = new StringWriter();
            command.setErr(new PrintWriter(errors));
            StringWriter output = new StringWriter();
            command.setOut(new PrintWriter(output));
            assertEquals(0, command.execute("doctor"), errors.toString());
            assertFalse(output.toString().contains("Listening:"), output.toString());
            assertFalse(output.toString().contains("started"), output.toString());
            assertTrue(output.toString().contains("Diagnostics"), output.toString());
        }
    }

    @Test void inferenceDoctorUsesAnEphemeralLoopbackListenerAndClosesIt() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Map<String, String> env = environment(occupied.getLocalPort());
            var root = new ProbeRoot(env);
            var command = AIProxyOauth.commandLine(root);
            StringWriter errors = new StringWriter();
            command.setErr(new PrintWriter(errors));
            command.setOut(new PrintWriter(new StringWriter()));
            assertEquals(0, command.execute("doctor", "--inference"), errors.toString());
            assertNotNull(root.probed);
            assertEquals("127.0.0.1", root.probed.host());
            assertNotEquals(occupied.getLocalPort(), root.probed.port());
            try (ServerSocket released = new ServerSocket(root.probed.port(), 1, InetAddress.getByName("127.0.0.1"))) {
                assertEquals(root.probed.port(), released.getLocalPort());
            }
        }
    }

    @Test void inferenceProbeUsesTheAdminKeyActuallyInstalledInTheServer() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
        Map<String, String> env = environment(port);
        Path admin = temporary.resolve("admin.txt");
        Files.writeString(admin, "file-admin");
        env.put("AIPROXY_ADMIN_CLIENT_KEY", "env-admin");
        env.put("AIPROXY_ADMIN_CLIENT_KEY_FILE", admin.toString());
        var root = new ProbeRoot(env);
        var command = AIProxyOauth.commandLine(root);
        StringWriter output = new StringWriter();
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(output));
        assertEquals(0, command.execute("doctor", "--inference"), output.toString());
        assertEquals("file-admin", root.probeKey);
    }

    @Test void busyCredentialStoreIsReportedWithoutBypassingItsLock() throws Exception {
        Map<String, String> env = environment(10531);
        try (var store = AnthropicCredentialStore.open(Path.of(env.get("AIPROXY_ANTHROPIC_OAUTH_FILE")))) {
            var command = AIProxyOauth.commandLine(new AIProxyOauth(() -> env));
            StringWriter errors = new StringWriter();
            command.setErr(new PrintWriter(errors));
            command.setOut(new PrintWriter(new StringWriter()));
            assertEquals(1, command.execute("doctor"));
            assertTrue(errors.toString().startsWith("Diagnostics failed:"), errors.toString());
            assertTrue(errors.toString().contains("already in use"), errors.toString());
            assertFalse(errors.toString().contains("at com.aiproxyoauth"), errors.toString());
            assertTrue(store.load().isEmpty());
        }
    }

    @Test void diagnosticListenerClosesWhenProbeThrows() throws Exception {
        var root = new ProbeRoot(environment(10531));
        root.failProbe = true;
        var command = AIProxyOauth.commandLine(root);
        command.setOut(new PrintWriter(new StringWriter()));
        command.setErr(new PrintWriter(new StringWriter()));
        assertEquals(1, command.execute("doctor", "--inference"));
        assertNotNull(root.probed);
        try (ServerSocket released = new ServerSocket(root.probed.port(), 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(root.probed.port(), released.getLocalPort());
        }
        try (var store = AnthropicCredentialStore.open(temporary.resolve("auth.json"))) {
            assertTrue(store.load().isEmpty());
        }
    }

    private static final class ProbeRoot extends AIProxyOauth {
        ServerConfig probed;
        String probeKey;
        boolean failProbe;
        ProbeRoot(Map<String, String> env) { super(() -> env); }
        @Override StartupProbeResult verifyAnthropicThroughProxy(ServerConfig config, List<String> models,
                                                                  String key, HttpClient http) {
            probed = config;
            probeKey = key;
            if (failProbe) throw new IllegalStateException("test probe failure");
            try {
                var request = HttpRequest.newBuilder(URI.create("http://" + config.host() + ":" + config.port() + "/v1/usage"))
                        .timeout(Duration.ofSeconds(5));
                if (key != null) request.header("Authorization", "Bearer " + key);
                var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
                return new StartupProbeResult(response.statusCode() == 200, response.statusCode(), "probe", null, "claude-test");
            } catch (Exception error) { throw new AssertionError(error); }
        }
    }
}
