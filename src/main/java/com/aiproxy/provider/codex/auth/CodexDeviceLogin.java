package com.aiproxy.provider.codex.auth;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Delegates the device protocol and its polling lifecycle to the official Codex CLI. */
public final class CodexDeviceLogin {
    @FunctionalInterface
    interface Runner { int run(Path home) throws IOException, InterruptedException; }
    private final Runner runner;

    CodexDeviceLogin(Runner runner) { this.runner = runner; }

    public static CodexDeviceLogin system() {
        return new CodexDeviceLogin(home -> {
            List<String> args = System.getProperty("os.name").startsWith("Windows")
                    ? List.of("cmd.exe", "/d", "/c", "codex", "login", "--device-auth", "-c", "cli_auth_credentials_store='file'")
                    : List.of("codex", "login", "--device-auth", "-c", "cli_auth_credentials_store='file'");
            var builder = new ProcessBuilder(args).inheritIO();
            builder.environment().put("CODEX_HOME", home.toString());
            Process process = builder.start();
            Thread cleanup = new Thread(() -> stop(process), "codex-device-login-cleanup");
            Runtime.getRuntime().addShutdownHook(cleanup);
            try {
                return process.waitFor();
            } finally {
                stop(process);
                try { Runtime.getRuntime().removeShutdownHook(cleanup); }
                catch (IllegalStateException ignored) { /* JVM already shutting down. */ }
            }
        });
    }

    private static void stop(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        if (process.isAlive()) process.destroyForcibly();
    }

    public int login(Path file, PrintWriter out, PrintWriter err) {
        Path target = file.toAbsolutePath().normalize();
        if (!target.getFileName().toString().equals("auth.json")) {
            err.println("Device login requires codex.oauth_file to be named auth.json (the official CLI file format/location).");
            err.flush();
            return 2;
        }
        try {
            Files.createDirectories(target.getParent());
            out.println("Starting official Codex CLI device login. Credential file: " + target);
            out.flush();
            int result = runner.run(target.getParent());
            if (result != 0) {
                err.println("Codex CLI device login failed or was cancelled (exit " + result + ").");
                err.flush();
                return result;
            }
            if (!Files.isRegularFile(target)) {
                err.println("Codex CLI did not create the expected auth.json credential file.");
                err.flush();
                return 1;
            }
            out.println("CLI login complete. Restart the proxy with --codex-auth-mode cli to use it. Native credentials are unchanged.");
            out.flush();
            return 0;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            err.println("Codex device login interrupted.");
        } catch (IOException error) {
            err.println("Cannot run Codex device login. Install the official Codex CLI on PATH and check the credential directory permissions.");
        }
        err.flush();
        return 1;
    }
}
