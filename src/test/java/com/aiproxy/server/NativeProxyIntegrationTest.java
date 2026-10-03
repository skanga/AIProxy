package com.aiproxy.server;

import com.aiproxy.bootstrap.ProviderAssembly;
import com.aiproxy.config.ServerConfig;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.codex.CodexHttpClient;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeProxyIntegrationTest {
    @Test void nativeChatResponsesToolsAndReplayUseAccountModelAndIsolatedHistory() throws Exception {
        CodexHttpClient upstream=mock(CodexHttpClient.class);
        when(upstream.isNative()).thenReturn(true);
        AtomicReference<String> identity=new AtomicReference<>("first-session");
        when(upstream.credentialIdentity()).thenAnswer(call->identity.get());
        AtomicReference<String> sent=new AtomicReference<>();
        AtomicInteger count=new AtomicInteger();
        when(upstream.request(anyString(),anyString(),anyString(),anyMap())).thenAnswer(call->{
            sent.set(call.getArgument(2));
            HttpResponse<InputStream> response=mock();
            when(response.statusCode()).thenReturn(200);
            String body="data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_native_"+count.incrementAndGet()+"\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}}\n\n";
            when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            return response;
        });
        ServerConfig config=new ServerConfig("127.0.0.1",10531,null,null,null,null,null,null,"",false,Map.of(),null);
        ProxyServer server=ProviderAssembly.create(config,upstream,
                ()->List.of(new ProviderModel("account-model","Account model",ProviderId.CODEX,List.of(),Optional.empty(),0)),
                new UsageTracker(),new ApiKeyStore(Map.of(),null,null));
        server.getApp().start("127.0.0.1",0);
        try(HttpClient http=HttpClient.newHttpClient()) {
            String root="http://127.0.0.1:"+server.getApp().port()+"/v1/";
            String chat="{\"messages\":[{\"role\":\"system\",\"content\":\"Be concise\"},{\"role\":\"user\",\"content\":\"Hi\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"strict\":true,\"parameters\":{\"type\":\"object\"}}}]}";
            var response=post(http,root+"chat/completions",chat);
            assertEquals(200,response.statusCode(),response.body());
            var wire=Json.MAPPER.readTree(sent.get());
            assertEquals("account-model",wire.path("model").asString());
            assertFalse(wire.path("store").asBoolean()); assertTrue(wire.path("stream").asBoolean());
            assertEquals("additional_tools",wire.at("/input/0/type").asString());
            assertEquals("lookup",wire.at("/input/0/tools/0/name").asString());
            assertTrue(wire.at("/input/0/tools/0/strict").asBoolean());
            assertEquals("Be concise",wire.path("instructions").asString());
            assertFalse(wire.has("tools"));
            response=post(http,root+"responses","{\"input\":\"first\",\"tools\":[{\"type\":\"function\",\"name\":\"lookup\"}]}");
            assertEquals(200,response.statusCode(),response.body());
            String id=Json.MAPPER.readTree(response.body()).path("id").asString();
            response=post(http,root+"responses","{\"input\":\"second\",\"previous_response_id\":\""+id+"\"}");
            assertEquals(200,response.statusCode(),response.body());
            wire=Json.MAPPER.readTree(sent.get());
            assertFalse(wire.has("previous_response_id"));
            assertEquals(4,wire.path("input").size());
            assertEquals("additional_tools",wire.at("/input/0/type").asString());
            String continued=Json.MAPPER.readTree(response.body()).path("id").asString();
            response=post(http,root+"responses","{\"input\":\"changed tool\",\"previous_response_id\":\""+continued
                    +"\",\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"new definition\"}]}");
            assertEquals(200,response.statusCode(),response.body());
            wire=Json.MAPPER.readTree(sent.get());
            assertEquals("new definition",wire.at("/input/5/tools/0/description").asString());
            identity.set("second-session"); // Real NativeSession rejects this change; handler also isolates caches.
            response=post(http,root+"responses","{\"input\":\"third\",\"previous_response_id\":\""+id+"\"}");
            assertEquals(400,response.statusCode(),response.body());
            assertEquals(4,count.get());
        } finally {server.stop();}
    }
    private static HttpResponse<String> post(HttpClient http,String url,String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
