package com.aiproxy.provider.codex;

import com.aiproxy.config.ServerConfig;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import io.javalin.http.Context;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodexNativeRequestProfileTest {
    private final CodexHttpClient client = mock(CodexHttpClient.class);
    private final ServerConfig config = new ServerConfig("127.0.0.1",10531,null,null,null,null,null,null,"",false,Map.of(),null);
    private Context context(String body) throws Exception {
        when(client.isNative()).thenReturn(true);
        when(client.credentialIdentity()).thenReturn("account-session");
        when(client.request(anyString(),anyString(),anyString(),anyMap()))
                .thenThrow(new AssertionError("Unsupported native request reached upstream"));
        Context ctx = mock(Context.class, RETURNS_SELF);
        when(ctx.body()).thenReturn(body);
        return ctx;
    }
    @ParameterizedTest @ValueSource(strings = {"temperature", "max_output_tokens", "metadata", "background", "top_p"})
    void unsupportedResponsesFieldsAreRejectedBeforeSending(String field) throws Exception {
        Context ctx = context(Json.MAPPER.createObjectNode().put("input","hi").put(field,1).toString());
        new CodexResponsesBackend(client,config,new UsageTracker()).handle(ctx);
        verify(ctx).status(400);
        verify(client,never()).request(anyString(),anyString(),anyString(),anyMap());
    }
    @Test void chatTokenLimitIsRejectedRatherThanSilentlyDropped() throws Exception {
        Context ctx = context("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":10}");
        new CodexChatBackend(client,config,new UsageTracker()).handle(ctx);
        verify(ctx).status(400);
        verify(client,never()).request(anyString(),anyString(),anyString(),anyMap());
    }
    @Test void missingReplayCannotBeSentToNativeUpstream() throws Exception {
        Context ctx = context("{\"input\":\"hi\",\"previous_response_id\":\"unknown\"}");
        new CodexResponsesBackend(client,config,new UsageTracker()).handle(ctx);
        verify(ctx).status(400);
        verify(client,never()).request(anyString(),anyString(),anyString(),anyMap());
    }
}
