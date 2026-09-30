package com.aiproxyoauth.auth.nativeoauth;

import com.aiproxyoauth.util.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NativeOAuthTest {
    @TempDir Path temporary;
    static KeyPair signingKey;
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1800000000), ZoneOffset.UTC);
    final HttpClient http = mock(HttpClient.class);
    final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
    final AtomicReference<String> tokenJson = new AtomicReference<>();
    NativeOAuth oauth;
    @BeforeAll static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); signingKey = generator.generateKeyPair();
    }
    @BeforeEach void setup() throws Exception {
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0); requests.add(request);
            String path = request.uri().getPath();
            String body = switch (path) {
                case "/.well-known/openid-configuration" -> "{\"issuer\":\"https://auth.openai.com\",\"authorization_endpoint\":\"https://auth.openai.com/api/accounts/authorize\",\"token_endpoint\":\"https://auth.openai.com/api/accounts/oauth/token\",\"jwks_uri\":\"https://auth.openai.com/.well-known/jwks.json\",\"revocation_endpoint\":\"https://auth.openai.com/revoke\"}";
                case "/.well-known/jwks.json" -> jwks();
                case "/api/accounts/oauth/token" -> tokenJson.get();
                case "/revoke" -> "";
                default -> throw new AssertionError("Unexpected OAuth endpoint " + path);
            };
            HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            return response;
        });
        oauth = new NativeOAuth(http,CLOCK);
    }
    static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String jwks() {
        RSAPublicKey key = (RSAPublicKey)signingKey.getPublic();
        return "{\"keys\":[{\"kid\":\"test-key\",\"kty\":\"RSA\",\"alg\":\"RS256\",\"n\":\""
                + b64(key.getModulus().toByteArray()) + "\",\"e\":\"" + b64(key.getPublicExponent().toByteArray()) + "\"}]}";
    }
    static ObjectNode claims(long now) {
        return Json.MAPPER.createObjectNode().put("iss",NativeOAuth.ISSUER).put("aud","oaiapp_test")
                .put("sub","account-one").put("exp",now+3600).put("iat",now).put("nonce","nonce");
    }
    static String sign(ObjectNode claims) throws Exception {
        String payload = b64("{\"alg\":\"RS256\",\"kid\":\"test-key\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(signingKey.getPrivate());
        signature.update(payload.getBytes(StandardCharsets.US_ASCII));
        return payload + "." + b64(signature.sign());
    }
    String tokens(ObjectNode claims) throws Exception {
        return Json.MAPPER.createObjectNode().put("access_token","new-access-secret").put("refresh_token","new-refresh-secret")
                .put("id_token",sign(claims)).put("scope",NativeOAuth.SCOPES).put("token_type","Bearer")
                .put("expires_in",3600).put("earliest_refresh_at",0).toString();
    }
    static Map<String,String> fields(String query) {
        Map<String,String> result = new HashMap<>();
        for (String pair : query.split("&")) { String[] kv=pair.split("=",2); result.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8)); }
        return result;
    }
    static String body(HttpRequest request) throws Exception {
        var subscriber = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
            public void onNext(java.nio.ByteBuffer value) { subscriber.onNext(List.of(value)); }
            public void onError(Throwable error) { subscriber.onError(error); }
            public void onComplete() { subscriber.onComplete(); }
        });
        return subscriber.getBody().toCompletableFuture().get(2,TimeUnit.SECONDS);
    }
    @Test void registrationUsesFreshPkceAndPublicResource() throws Exception {
        var uri = oauth.authorize(null,"host","http://127.0.0.1:1234/auth/callback","state","nonce","verifier");
        Map<String,String> params = fields(uri.getRawQuery());
        assertEquals("dynamic_agent_client",params.get("client_id"));
        assertEquals("AIProxyOauth",params.get("agent_name_hint"));
        assertEquals(NativeOAuth.RESOURCE,params.get("resource"));
        assertEquals(b64(MessageDigest.getInstance("SHA-256").digest("verifier".getBytes(StandardCharsets.US_ASCII))),params.get("code_challenge"));
        var returning = fields(oauth.authorize("oaiapp_test","host","http://127.0.0.1:1234/auth/callback","state2","nonce2","v2").getRawQuery());
        assertFalse(returning.containsKey("agent_name_hint"));
        assertFalse(returning.containsKey("id_token_hint"));
    }
    @Test void exchangeUsesIssuedClientAndValidatesIdentity() throws Exception {
        tokenJson.set(tokens(claims(CLOCK.instant().getEpochSecond())));
        var value = oauth.exchange("code","oaiapp_test","host","http://127.0.0.1:1234/auth/callback","verifier","nonce",null);
        assertEquals("account-one",value.subject());
        var form = fields(body(requests.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow()));
        assertEquals("oaiapp_test",form.get("client_id")); assertEquals("verifier",form.get("code_verifier"));
        assertFalse(value.toString().contains("secret"));
    }
    @ParameterizedTest @ValueSource(strings={"nonce","iss","aud","exp","iat","sub","signature"})
    void rejectsInvalidIdentity(String field) throws Exception {
        ObjectNode claims = claims(CLOCK.instant().getEpochSecond());
        if (field.equals("exp")) claims.put("exp",1);
        else if (field.equals("iat")) claims.put("iat",Long.MAX_VALUE);
        else if (field.equals("sub")) claims.remove("sub");
        else if (!field.equals("signature")) claims.put(field,"wrong");
        String token = sign(claims);
        if (field.equals("signature")) { String[] parts = token.split("\\."); token = parts[0]+"."+parts[1]+"."+b64(new byte[256]); }
        String candidate = token;
        assertThrows(Exception.class, () -> oauth.verifyIdentity(candidate,"oaiapp_test","nonce"));
    }
    @Test void missingPermissionCannotBePersisted() throws Exception {
        tokenJson.set(tokens(claims(CLOCK.instant().getEpochSecond())).replace(NativeOAuth.SCOPES,"openid"));
        assertThrows(IOException.class,()->oauth.exchange("code","oaiapp_test","host","http://127.0.0.1:1/auth/callback","verifier","nonce",null));
    }
    @Test void callbackRejectsWrongStateDuplicatesAndChangedClient() {
        assertThrows(IOException.class,()->NativeAuthCommands.callbackFields("code=x&state=wrong&client_id=oaiapp_test","state",null));
        assertThrows(IOException.class,()->NativeAuthCommands.callbackFields("code=x&state=state&state=state&client_id=oaiapp_test","state",null));
        assertThrows(IOException.class,()->NativeAuthCommands.callbackFields("code=x&state=state&client_id=oaiapp_other","state","oaiapp_test"));
        assertDoesNotThrow(()->NativeAuthCommands.callbackFields("error=access_denied&state=state","state",null));
        assertEquals("oaiapp_test",assertDoesNotThrow(()->NativeAuthCommands.callbackFields("code=x&state=state","state","oaiapp_test")).get("client_id"));
    }
    @Test void confirmedLogoutRecoversCorruptNativeFileWithoutTouchingOtherCredentials() throws Exception {
        Path file = temporary.resolve("native.json"), external = temporary.resolve("cli-auth.json");
        Files.writeString(file,"{broken"); Files.writeString(external,"external-credentials");
        var output = new StringWriter(); var errors = new StringWriter();
        try (var auth = new NativeAuthCommands(file,http,new PrintWriter(output),new PrintWriter(errors))) {
            assertEquals(0,auth.logout(true),errors.toString());
        }
        assertFalse(Files.exists(file)); assertEquals("external-credentials",Files.readString(external));
        assertTrue(errors.toString().contains("revocation was not confirmed"));
        verify(http,never()).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    NativeCredential credential(long expires) {
        return new NativeCredential("oaiapp_test","account-one","host","session","old-id","old-access","old-refresh",NativeOAuth.SCOPES,expires,0);
    }
    @Test void concurrentSessionsRotateOnceAndObserveLogout() throws Exception {
        NativeCredentialStore store = new NativeCredentialStore(temporary.resolve("native.json"));
        store.locked(()-> {store.save(credential(CLOCK.instant().getEpochSecond()+10)); return null;});
        tokenJson.set(tokens(claims(CLOCK.instant().getEpochSecond())));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<NativeCredential>> futures = new ArrayList<>();
            for (int i=0;i<8;i++) futures.add(executor.submit(()->new NativeSession(new NativeCredentialStore(store.path()),oauth,CLOCK).current()));
            for (var result : futures) assertEquals("new-refresh-secret",result.get(5,TimeUnit.SECONDS).refreshToken());
        }
        assertEquals(1,requests.stream().filter(r->r.method().equals("POST")).count());
        var refresh = fields(body(requests.stream().filter(r->r.method().equals("POST")).findFirst().orElseThrow()));
        assertEquals("old-refresh",refresh.get("refresh_token")); assertFalse(refresh.containsKey("scope"));
        NativeSession session = new NativeSession(store,oauth,CLOCK); session.current();
        store.locked(()-> {store.clear(store.read()); return null;});
        assertThrows(IOException.class,session::current);
        assertNotNull(store.locked(store::registration));
    }
    @Test void reloginInvalidatesRunningSession() throws Exception {
        NativeCredentialStore store = new NativeCredentialStore(temporary.resolve("native.json"));
        NativeCredential first = credential(CLOCK.instant().getEpochSecond()+3600);
        store.locked(()-> {store.save(first); return null;});
        NativeSession session = new NativeSession(store,oauth,CLOCK); session.current();
        var next = new NativeCredential(first.clientId(),first.subject(),first.hostId(),"new-session",first.idToken(),first.accessToken(),first.refreshToken(),first.scope(),first.expiresAt(),0);
        store.locked(()-> {store.save(next); return null;});
        assertThrows(IOException.class,session::current);
    }
    @Test void rotatedTokensAreNotUsedWhenPersistenceFails() throws Exception {
        NativeCredentialStore store = spy(new NativeCredentialStore(temporary.resolve("native.json")));
        NativeCredential old = credential(CLOCK.instant().getEpochSecond()+10);
        store.locked(()-> {store.save(old); return null;});
        tokenJson.set(tokens(claims(CLOCK.instant().getEpochSecond())));
        doThrow(new IOException("Disk unavailable")).when(store).save(any(NativeCredential.class));
        assertThrows(IOException.class,()->new NativeSession(store,oauth,CLOCK).current());
        assertEquals(old,store.locked(store::read));
    }
    @Test void earliestRefreshTimeIsHonoredAndCredentialPermissionsAreRestricted() throws Exception {
        NativeCredentialStore store = new NativeCredentialStore(temporary.resolve("native.json"));
        long now=CLOCK.instant().getEpochSecond();
        NativeCredential credential = new NativeCredential("oaiapp_test","subject","host","session","id","access","refresh",
                NativeOAuth.SCOPES,now+200,now+100);
        store.locked(()-> {store.save(credential); return null;});
        assertEquals("access",new NativeSession(store,oauth,CLOCK).current().accessToken());
        verifyNoInteractionsExceptRedirectPolicy();
        var acl=Files.getFileAttributeView(store.path(),java.nio.file.attribute.AclFileAttributeView.class);
        if (acl!=null) {
            assertTrue(acl.getAcl().stream().allMatch(entry->entry.principal().equals(assertDoesNotThrow(acl::getOwner))));
        } else {
            assertEquals(Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),Files.getPosixFilePermissions(store.path()));
        }
    }
    private void verifyNoInteractionsExceptRedirectPolicy() throws Exception {
        verify(http,never()).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @ParameterizedTest @ValueSource(strings={"first","returning","new","cancelled"})
    void browserCallbackCompletesLoginAndPersistsOnlyAfterVerification(String mode) throws Exception {
        var output = new StringWriter(); var errors = new StringWriter();
        Path file = temporary.resolve("native.json");
        if (mode.equals("returning") || mode.equals("new")) {
            var store = new NativeCredentialStore(file);
            store.locked(()-> {
                store.save(new NativeCredential("oaiapp_test",mode.equals("new") ? "old-account" : "account-one",
                        store.hostId(),"old-session","old-id","old-access","old-refresh",NativeOAuth.SCOPES,
                        Instant.now().getEpochSecond()+3600,0));
                return null;
            });
        }
        try (var auth = new NativeAuthCommands(file,http,new PrintWriter(output),new PrintWriter(errors));
             var executor = Executors.newVirtualThreadPerTaskExecutor(); HttpClient browser = HttpClient.newHttpClient()) {
            Future<Integer> login = executor.submit(()->auth.login(true,mode.equals("new")));
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            URI authorization = null;
            while (authorization == null && System.nanoTime()<deadline) {
                authorization = output.toString().lines().filter(l->l.startsWith("https://")).map(URI::create).findFirst().orElse(null);
                if (authorization == null) Thread.sleep(10);
            }
            assertNotNull(authorization,errors.toString());
            var params = fields(authorization.getRawQuery());
            assertEquals(mode.equals("returning") ? "oaiapp_test" : "dynamic_agent_client",params.get("client_id"));
            if (mode.equals("cancelled")) assertEquals(0,auth.logout(true));
            tokenJson.set(tokens(claims(Instant.now().getEpochSecond()).put("nonce",params.get("nonce"))));
            String callback = params.get("redirect_uri");
            assertEquals("127.0.0.1",URI.create(callback).getHost());
            assertEquals(400,browser.send(HttpRequest.newBuilder(URI.create(callback+"?state=bad&code=x&client_id=oaiapp_test")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(mode.equals("returning") || mode.equals("new"),Files.exists(file));
            var callbackResponse = browser.send(HttpRequest.newBuilder(URI.create(callback+"?state="+params.get("state")+"&code=x&client_id=oaiapp_test")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,callbackResponse.statusCode());
            assertEquals("Login response received. Return to the terminal to check completion.",callbackResponse.body());
            if (mode.equals("cancelled")) {
                assertEquals(1,login.get(5,TimeUnit.SECONDS),errors.toString());
                assertFalse(Files.exists(file));
                return;
            }
            assertEquals(0,login.get(5,TimeUnit.SECONDS),errors.toString());
            assertEquals("account-one",new NativeCredentialStore(file).locked(()->NativeCredential.parse(Json.MAPPER.readTree(Files.readString(file)))).subject());
            assertFalse(output.toString().contains("new-refresh-secret"));
        }
    }
}
