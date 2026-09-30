package com.aiproxyoauth.auth;

import tools.jackson.databind.JsonNode;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.util.JwtParser;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class AuthManager {

    private static final long REFRESH_EXPIRY_MARGIN_MS = 5 * 60 * 1000L;

    private final ServerConfig config;
    private final HttpClient httpClient;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile AuthLoader.AuthResult current;
    private com.aiproxyoauth.auth.nativeoauth.NativeSession nativeSession;
    private String selectedFile;

    public AuthManager(ServerConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        this.selectedFile = config.oauthFilePath();
    }

    public AuthManager(ServerConfig config, HttpClient httpClient,
                       com.aiproxyoauth.auth.nativeoauth.NativeSession nativeSession) {
        this(config, httpClient);
        this.nativeSession = nativeSession;
    }
    public AuthManager(ServerConfig config, HttpClient httpClient,
                       com.aiproxyoauth.auth.nativeoauth.NativeSession nativeSession, String selectedFile) {
        this(config, httpClient, nativeSession);
        this.selectedFile = selectedFile;
    }
    public boolean isNative() { return nativeSession != null; }
    public String nativeIdentity() throws Exception {
        if (!isNative()) return "cli";
        ensureFresh();
        return nativeSession.identity();
    }

    public AuthLoader.AuthResult ensureFresh() throws Exception {
        if (isNative()) {
            try {
                var nativeCredential = nativeSession.current();
                current = new AuthLoader.AuthResult(nativeCredential.accessToken(), nativeCredential.subject(),
                        nativeCredential.idToken(), nativeCredential.refreshToken(), nativeSession.source(), null);
                return current;
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new com.aiproxyoauth.auth.nativeoauth.NativeAuthException();
            }
        }
        lock.lock();
        try {
            // Re-check after acquiring the lock: another thread may have already refreshed.
            AuthLoader.AuthResult existing = current;
            if (existing != null && !isTokenExpiringSoon(existing.accessToken())) {
                return existing;
            }
            current = AuthLoader.loadAuthTokens(
                    selectedFile,
                    config.oauthClientId(),
                    null, // issuer derived from defaults
                    config.oauthTokenUrl(),
                    httpClient
            );
            return current;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, String> getAuthHeaders() throws Exception {
        if (isNative()) return Map.of("Authorization", "Bearer " + ensureFresh().accessToken());
        AuthLoader.AuthResult auth = current;
        if (auth == null || isTokenExpiringSoon(auth.accessToken())) {
            auth = ensureFresh();
        }
        // Map.of() rejects null values with NullPointerException. accountId() is safe here
        // because AuthLoader.loadAuthTokens() throws IOException before returning an AuthResult
        // with a null or empty accountId, so ensureFresh() would have propagated that exception
        // before we reach this point.
        return Map.of(
                "Authorization", "Bearer " + auth.accessToken(),
                "chatgpt-account-id", auth.accountId(),
                "OpenAI-Beta", "responses=experimental"
        );
    }

    private static boolean isTokenExpiringSoon(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) return true;
        JsonNode claims = JwtParser.parseClaims(accessToken);
        if (claims != null && claims.has("exp") && claims.get("exp").isNumber()) {
            long expiryMs = claims.get("exp").asLong() * 1000;
            return expiryMs <= System.currentTimeMillis() + REFRESH_EXPIRY_MARGIN_MS;
        }
        return false;
    }

    public AuthLoader.AuthResult getCurrent() {
        return current;
    }
}
