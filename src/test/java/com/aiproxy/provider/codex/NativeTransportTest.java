package com.aiproxy.provider.codex;

import com.aiproxy.config.ServerConfig;
import com.aiproxy.provider.codex.auth.CodexAuthManager;
import com.aiproxy.provider.codex.model.CodexModelResolver;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeTransportTest {
    @Test void nativeBearerCannotReachLegacyOrArbitraryEndpointsOrCarryLegacyHeaders() throws Exception {
        var auth = mock(CodexAuthManager.class); when(auth.isNative()).thenReturn(true);
        when(auth.getAuthHeaders()).thenReturn(Map.of("Authorization","Bearer native-secret"));
        var http = mock(HttpClient.class); when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        HttpResponse<String> response = mock(); when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");
        when(http.<String>send(any(HttpRequest.class),any())).thenAnswer(call->{
            HttpRequest sent = call.getArgument(0);
            assertEquals("https://api.openai.com/v1/models",sent.uri().toString());
            assertEquals("Bearer native-secret",sent.headers().firstValue("Authorization").orElseThrow());
            for (String header : List.of("chatgpt-account-id","OpenAI-Beta","conversation_id","session_id"))
                assertTrue(sent.headers().firstValue(header).isEmpty(),header);
            return response;
        });
        var config = new ServerConfig("127.0.0.1",10531,null,null,"https://attacker.invalid",null,null,null,"",false,Map.of(),null);
        var client = new CodexHttpClient(config,http,auth);
        client.requestString("/models","GET",null,Map.of("chatgpt-account-id","legacy","Authorization","Bearer bad"));
        assertThrows(IllegalArgumentException.class,()->client.requestString("https://attacker.invalid/","GET",null,null));
        assertThrows(IllegalArgumentException.class,()->client.requestString("/models?client_version=x","GET",null,null));
        verify(http,times(1)).send(any(HttpRequest.class),any());
    }
    @Test void nativeCatalogUsesAccountModelsAndAppliesConfiguredFilter() throws Exception {
        var client = mock(CodexHttpClient.class); when(client.isNative()).thenReturn(true);
        when(client.credentialIdentity()).thenReturn("session");
        HttpResponse<String> response = mock(); when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"models\":[{\"slug\":\"visible\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hide\"},{\"slug\":\"other\",\"visibility\":\"list\"}]}");
        when(client.requestString("/models","GET",null,null)).thenReturn(response);
        var resolver = new CodexModelResolver(client,List.of("visible","hidden","invented"),null);
        assertEquals(List.of("visible"),resolver.resolveModels());
        assertEquals(List.of("visible"),resolver.resolveModels());
        verify(client,times(1)).requestString("/models","GET",null,null);
        verify(client,never()).getHttpClient();
        when(client.credentialIdentity()).thenThrow(new com.aiproxy.provider.codex.auth.nativeoauth.NativeAuthException());
        assertThrows(com.aiproxy.provider.codex.auth.nativeoauth.NativeAuthException.class,resolver::resolveModels);
    }
}
