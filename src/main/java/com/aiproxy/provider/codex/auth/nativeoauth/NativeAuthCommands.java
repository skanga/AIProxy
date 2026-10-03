package com.aiproxy.provider.codex.auth.nativeoauth;

import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Browser login owns a temporary IPv4 loopback listener, never the proxy's serving listener. */
public final class NativeAuthCommands implements AutoCloseable {
    private final NativeCredentialStore store;
    private final HttpClient http;
    private final NativeOAuth oauth;
    private final PrintWriter out;
    private final PrintWriter err;
    public static NativeAuthCommands system(Path path, PrintWriter out, PrintWriter err) {
        return new NativeAuthCommands(path, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), out, err);
    }
    NativeAuthCommands(Path path, HttpClient http, PrintWriter out, PrintWriter err) {
        this.store = new NativeCredentialStore(path); this.http = http;
        this.oauth = new NativeOAuth(http,Clock.systemUTC()); this.out = out; this.err = err;
    }
    record Start(NativeCredential credential, String clientId, String subject, String host, String logoutGeneration) {}
    public int login(boolean noBrowser) {
        return login(noBrowser, false);
    }
    public int login(boolean noBrowser, boolean newAccount) {
        try {
            Start start = store.locked(() -> {
                NativeCredential old = store.read();
                var registration = newAccount ? null : old == null ? store.registration() : old.registration();
                String host = store.hostId();
                if (registration != null && (!NativeOAuth.ISSUER.equals(registration.path("issuer").asString())
                        || !host.equals(registration.path("host_id").asString())))
                    throw new IOException("Native account registration belongs to a different issuer or host");
                return new Start(old, registration == null ? null : NativeCredential.text(registration,"client_id"),
                        registration == null ? null : NativeCredential.text(registration,"subject"),host,store.logoutGeneration());
            });
            String state = NativeOAuth.random(), nonce = NativeOAuth.random(), verifier = NativeOAuth.random();
            HttpServer callback = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0), 8);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                callback.setExecutor(executor);
                String redirect = "http://127.0.0.1:" + callback.getAddress().getPort() + "/auth/callback";
                CompletableFuture<Map<String,String>> result = new CompletableFuture<>();
                var claimed = new java.util.concurrent.atomic.AtomicBoolean();
                callback.createContext("/auth/callback", exchange -> {
                    int status = 400;
                    Map<String,String> accepted = null;
                    String message = "Invalid login callback. Return to the terminal.";
                    try {
                        if (!"GET".equals(exchange.getRequestMethod()) || !"/auth/callback".equals(exchange.getRequestURI().getPath()))
                            throw new IOException("Invalid callback");
                        Map<String,String> fields = callbackFields(exchange.getRequestURI().getRawQuery(),state,start.clientId());
                        if (claimed.compareAndSet(false, true)) {
                            accepted = fields;
                            status = 200;
                            message = "Login response received. Return to the terminal to check completion.";
                        }
                    } catch (Exception invalid) { /* No codes or tokens in response or logs. */ }
                    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
                    exchange.getResponseHeaders().set("Cache-Control","no-store");
                    try {
                        exchange.sendResponseHeaders(status,bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } finally {
                        exchange.close();
                        // Login may stop the listener as soon as this future completes.
                        // Finish the browser response before allowing that shutdown.
                        if (accepted != null) result.complete(accepted);
                    }
                });
                try {
                    callback.start();
                    URI url = oauth.authorize(start.clientId(),start.host(),redirect,state,nonce,verifier);
                    out.println("Open this URL on this computer to sign in with ChatGPT:");
                    out.println(url); out.flush();
                    if (!noBrowser) {
                        try { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(url); }
                        catch (Exception unavailable) { err.println("Browser could not be opened; use the URL above."); }
                    }
                    Map<String,String> fields = result.get(10,TimeUnit.MINUTES);
                    if (fields.containsKey("error")) throw new IOException("ChatGPT sign-in was denied or cancelled");
                    NativeCredential credential = oauth.exchange(fields.get("code"),fields.get("client_id"),start.host(),
                            redirect,verifier,nonce,start.subject());
                    store.locked(() -> {
                        if (!Objects.equals(store.read(),start.credential())
                                || !store.logoutGeneration().equals(start.logoutGeneration()))
                            throw new IOException("Credentials changed during login; retry login");
                        if (newAccount) store.archiveRegistration();
                        store.save(credential);
                        return null;
                    });
                    out.println("Native Codex login saved. Restart running proxies to use this login."); out.flush();
                    return 0;
                } finally { callback.stop(0); }
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            err.println(error instanceof TimeoutException ? "ChatGPT sign-in timed out; run login again."
                    : "Native Codex login failed: " + safeError(error)); err.flush();
            return 1;
        }
    }
    static Map<String,String> callbackFields(String query, String state, String savedClient) throws IOException {
        if (query == null || query.length() > 16384) throw new IOException("Invalid callback");
        Map<String,String> fields = new HashMap<>();
        try {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=",2);
                String key = URLDecoder.decode(kv[0],StandardCharsets.UTF_8);
                String value = kv.length == 2 ? URLDecoder.decode(kv[1],StandardCharsets.UTF_8) : "";
                if (fields.putIfAbsent(key,value) != null) throw new IOException("Duplicate callback parameter");
            }
        } catch (IllegalArgumentException error) { throw new IOException("Invalid callback encoding"); }
        if (!state.equals(fields.get("state"))) throw new IOException("Login state mismatch");
        if (fields.containsKey("error")) return fields;
        if (fields.getOrDefault("code","").isBlank()) throw new IOException("Missing authorization code");
        String client = fields.getOrDefault("client_id",savedClient);
        if (client == null || !client.startsWith("oaiapp_") || (savedClient != null && !savedClient.equals(client)))
            throw new IOException("Unexpected OAuth client");
        fields.put("client_id",client);
        return fields;
    }
    public int logout(boolean yes) {
        if (!yes) {
            var console = System.console();
            if (console == null || !"yes".equalsIgnoreCase(console.readLine("Sign out of native Codex? Type yes: "))) {
                err.println("Logout cancelled; use --yes to confirm in a noninteractive terminal."); return 1;
            }
        }
        try {
            boolean revoked = store.locked(() -> {
                NativeCredential current = null;
                boolean readable = true;
                try { current = store.read(); }
                catch (IOException invalid) { readable = false; }
                boolean remote = readable && current == null;
                if (current != null) {
                    try { remote = oauth.revoke(current); }
                    catch (Exception failure) { if (failure instanceof InterruptedException) Thread.currentThread().interrupt(); }
                }
                store.clear(current);
                return remote;
            });
            out.println("Native Codex tokens cleared. Saved client registration retained; CLI credentials were not changed.");
            if (!revoked) err.println("Remote revocation was not confirmed. Disconnect AIProxy in ChatGPT Settings if needed.");
            out.flush(); err.flush(); return 0;
        } catch (Exception error) { err.println("Native Codex logout failed: " + safeError(error)); err.flush(); return 1; }
    }
    private static String safeError(Exception error) {
        // Only our fixed, secret-free messages are exposed; network/parser exceptions can contain payloads.
        String message = error.getMessage();
        return error.getClass() == IOException.class && message != null
                ? message : "Could not complete authentication; retry the command.";
    }
    @Override public void close() { http.close(); }
}
