package com.aiproxyoauth.auth.nativeoauth;

import com.aiproxyoauth.util.Json;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** Public-client OAuth for the documented ChatGPT plan grant. No legacy Codex endpoints. */
public final class NativeOAuth {
    public static final String ISSUER = "https://auth.openai.com";
    public static final String RESOURCE = "https://api.openai.com/v1";
    static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private final HttpClient http;
    private final Clock clock;
    private JsonNode discovery;
    public NativeOAuth(HttpClient http, Clock clock) {
        if (http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Native OAuth HTTP client must not follow redirects");
        this.http = http;
        this.clock = clock;
    }
    static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String form(Map<String,String> fields) {
        return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    private synchronized URI endpoint(String key) throws Exception {
        if (discovery == null) {
            JsonNode metadata = get(URI.create(ISSUER + "/.well-known/openid-configuration"));
            if (!ISSUER.equals(metadata.path("issuer").asString())) throw new IOException("Unexpected OpenAI issuer");
            discovery = metadata;
        }
        URI uri = URI.create(NativeCredential.text(discovery, key));
        if (!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IOException("Untrusted OpenAI OAuth endpoint");
        return uri;
    }
    URI authorize(String clientId, String host, String redirect, String state, String nonce, String verifier) throws Exception {
        Map<String,String> fields = new LinkedHashMap<>();
        fields.put("client_id",clientId == null ? "dynamic_agent_client" : clientId);
        if (clientId == null) fields.put("agent_name_hint","AIProxyOauth");
        fields.put("ext_agent_host_id",host);
        fields.put("redirect_uri",redirect);
        fields.put("response_type","code");
        fields.put("scope",SCOPES);
        fields.put("resource",RESOURCE);
        fields.put("state",state);
        fields.put("nonce",nonce);
        fields.put("code_challenge_method","S256");
        fields.put("code_challenge",Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))));
        // Deliberately omit optional ID-token hints: the displayed URL never contains a saved token.
        return URI.create(endpoint("authorization_endpoint") + "?" + form(fields));
    }
    NativeCredential exchange(String code, String clientId, String host, String redirect,
                              String verifier, String nonce, String expectedSubject) throws Exception {
        JsonNode tokens = post(endpoint("token_endpoint"), Map.of("grant_type","authorization_code", "client_id",clientId,
                "code",code,"code_verifier",verifier,"redirect_uri",redirect,"resource",RESOURCE));
        return credential(tokens,clientId,host,UUID.randomUUID().toString(),nonce,expectedSubject,null);
    }
    NativeCredential refresh(NativeCredential current) throws Exception {
        JsonNode tokens = post(endpoint("token_endpoint"),Map.of("grant_type","refresh_token","client_id",current.clientId(),
                "refresh_token",current.refreshToken(),"resource",RESOURCE));
        return credential(tokens,current.clientId(),current.hostId(),current.sessionId(),null,current.subject(),current);
    }
    boolean revoke(NativeCredential current) throws Exception {
        HttpRequest request = request(endpoint("revocation_endpoint")).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token",current.refreshToken(),
                        "token_type_hint","refresh_token","client_id",current.clientId())))).build();
        HttpResponse<InputStream> response = http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) { return response.statusCode() == 200; }
    }
    private NativeCredential credential(JsonNode tokens, String clientId, String host, String session,
                                        String nonce, String expectedSubject, NativeCredential previous) throws Exception {
        if (!"Bearer".equalsIgnoreCase(NativeCredential.text(tokens,"token_type"))) throw new IOException("Unsupported OAuth token type");
        String idToken = tokens.has("id_token") ? NativeCredential.text(tokens,"id_token") : previous == null ? null : previous.idToken();
        String subject;
        if (tokens.has("id_token")) subject = verifyIdentity(idToken,clientId,nonce);
        else if (previous != null) subject = previous.subject();
        else throw new IOException("OpenAI did not return an ID token");
        if (expectedSubject != null && !expectedSubject.equals(subject)) throw new IOException("OpenAI account changed; use auth codex login --new-account to register another account");
        long expires = NativeCredential.number(tokens,"expires_in");
        if (expires <= 0 || expires > 86400) throw new IOException("Invalid OAuth token lifetime");
        long now = clock.instant().getEpochSecond();
        long earliest = tokens.has("earliest_refresh_at") ? NativeCredential.number(tokens,"earliest_refresh_at") : 0;
        if (earliest < 0 || earliest > now + expires) throw new IOException("Invalid OAuth refresh time");
        String scope = tokens.has("scope") ? NativeCredential.text(tokens,"scope") : previous == null ? "" : previous.scope();
        NativeCredential result = new NativeCredential(clientId,subject,host,session,idToken,
                NativeCredential.text(tokens,"access_token"),NativeCredential.text(tokens,"refresh_token"),scope,now + expires,earliest);
        result.requireScope();
        return result;
    }
    String verifyIdentity(String token, String clientId, String nonce) throws Exception {
        try {
            String[] parts = token.split("\\.",-1);
            if (parts.length != 3) throw new IOException("Invalid ID token");
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JsonNode header = Json.MAPPER.readTree(decoder.decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asString()) || header.has("crit")) throw new IOException("Unsupported ID token algorithm");
            String kid = NativeCredential.text(header,"kid");
            JsonNode jwks = get(endpoint("jwks_uri"));
            JsonNode selected = null;
            if (!jwks.path("keys").isArray()) throw new IOException("Invalid OpenAI signing keys");
            for (JsonNode key : jwks.path("keys")) {
                if (kid.equals(key.path("kid").asString()) && "RSA".equals(key.path("kty").asString())
                        && (!key.has("use") || "sig".equals(key.path("use").asString()))
                        && (!key.has("alg") || "RS256".equals(key.path("alg").asString()))) {
                    if (selected != null) throw new IOException("Ambiguous OpenAI signing key");
                    selected = key;
                }
            }
            if (selected == null) throw new IOException("Unknown OpenAI signing key");
            BigInteger modulus = new BigInteger(1,decoder.decode(NativeCredential.text(selected,"n")));
            if (modulus.bitLength() < 2048) throw new IOException("Invalid OpenAI signing key");
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,
                    new BigInteger(1,decoder.decode(NativeCredential.text(selected,"e")))));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decoder.decode(parts[2]))) throw new IOException("Invalid ID token signature");
            JsonNode claims = Json.MAPPER.readTree(decoder.decode(parts[1]));
            JsonNode aud = claims.path("aud");
            boolean audience = aud.isString() && clientId.equals(aud.asString());
            if (aud.isArray()) for (JsonNode a : aud) audience |= a.isString() && clientId.equals(a.asString());
            long now = clock.instant().getEpochSecond();
            if (!ISSUER.equals(NativeCredential.text(claims,"iss")) || !audience
                    || (aud.isArray() && aud.size() > 1 && !clientId.equals(claims.path("azp").asString()))
                    || (claims.has("azp") && !clientId.equals(claims.path("azp").asString()))
                    || NativeCredential.number(claims,"exp") <= now - 5
                    || NativeCredential.number(claims,"iat") > now + 5
                    || (claims.has("nbf") && NativeCredential.number(claims,"nbf") > now + 5)
                    || (nonce != null && !nonce.equals(claims.path("nonce").asString())))
                throw new IOException("Invalid ID token claims");
            return NativeCredential.text(claims,"sub");
        } catch (IllegalArgumentException error) { throw new IOException("Invalid ID token"); }
    }
    private HttpRequest.Builder request(URI uri) { return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept","application/json"); }
    private JsonNode get(URI uri) throws Exception { return send(request(uri).GET().build()); }
    private JsonNode post(URI uri, Map<String,String> fields) throws Exception {
        return send(request(uri).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build());
    }
    private JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<InputStream> response = http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("OpenAI OAuth request failed (HTTP " + response.statusCode() + "); sign in again if access was revoked");
            byte[] bytes = body.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("OpenAI OAuth response too large");
            try { return Json.MAPPER.readTree(bytes); }
            catch (RuntimeException error) { throw new IOException("Invalid OpenAI OAuth response"); }
        }
    }
}
