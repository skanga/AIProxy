package com.aiproxy.provider.copilot;

import com.aiproxy.protocol.chat.ChatRequestDecoder;
import com.aiproxy.protocol.responses.ResponsesRequestDecoder;
import com.aiproxy.provider.spi.CompletionEvent;
import com.aiproxy.util.Json;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CopilotProtocolTest {
    @Test void preservesCopilotUpstreamOptionsWhenTheClientRequestsJson() throws Exception {
        var request = new ChatRequestDecoder().decode(Json.MAPPER.readTree("""
                {"messages":[{"role":"user","content":"hi"}],"stream":false,
                 "max_tokens":17,"temperature":0.5,"top_p":0.8,"reasoning_effort":"high"}
                """), "test");
        assertFalse(request.stream());
        var chat = CopilotRequestEncoder.encode(request, "/chat/completions");
        assertTrue(chat.path("stream").asBoolean());
        assertTrue(chat.at("/stream_options/include_usage").asBoolean());
        assertEquals(17, chat.path("max_completion_tokens").asInt());
        assertEquals("high", chat.path("reasoning_effort").asString());
        assertEquals(0.5, chat.path("temperature").asDouble());
        assertEquals(0.8, chat.path("top_p").asDouble());
        assertFalse(chat.has("store"));

        var responses = CopilotRequestEncoder.encode(request, "/responses");
        assertTrue(responses.path("stream").asBoolean());
        assertTrue(responses.path("store").isBoolean());
        assertFalse(responses.path("store").asBoolean());
        assertEquals(17, responses.path("max_output_tokens").asInt());
        assertEquals("high", responses.at("/reasoning/effort").asString());
        assertFalse(responses.has("stream_options"));
    }

    @Test void preservesCopilotFallbackMetadataAndProtocolErrors() {
        for (boolean responses : new boolean[]{false, true}) {
            var decoder = new CopilotStreamDecoder(responses);
            String start = responses ? "{\"type\":\"response.created\",\"response\":{}}"
                    : "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}";
            var events = decoder.feed(("data: " + start + "\n\n").getBytes(StandardCharsets.UTF_8));
            var started = (CompletionEvent.Started) events.getFirst();
            assertTrue(started.id().startsWith("copilot-"));
            assertEquals("copilot", started.model());
            var failed = decoder.end();
            assertEquals(1, failed.size());
            var error = (CompletionEvent.Error) failed.getFirst();
            assertEquals("Copilot returned a malformed, failed, or incomplete stream", error.error().message());
            assertTrue(decoder.end().isEmpty());
            assertTrue(decoder.feed("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
        }
    }

    @Test void preservesMessagesAdjustmentsAndResponsesStopRejection() throws Exception {
        var request = new ChatRequestDecoder().decode(Json.MAPPER.readTree("""
                {"messages":[{"role":"user","content":"hi"}],"stop":["end"]}
                """), "test");
        var chat = CopilotRequestEncoder.encode(request, "/chat/completions");
        assertEquals("end", chat.path("stop").get(0).asString());
        var messages = CopilotRequestEncoder.encode(request, "/v1/messages");
        assertTrue(messages.path("stream").asBoolean());
        assertFalse(messages.has("system"));
        var error = assertThrows(IllegalArgumentException.class,
                () -> CopilotRequestEncoder.encode(request, "/responses"));
        assertEquals("stop is unsupported by the Copilot Responses protocol", error.getMessage());
    }

    @Test void translatesResponsesToolsToEveryProtocolWithoutClaudeIdentity() throws Exception {
        var request = new ResponsesRequestDecoder().decode(Json.MAPPER.readTree("""
                {"instructions":"Be concise","input":[{"type":"function_call","call_id":"call1","name":"lookup","arguments":"{}"},
                {"type":"function_call_output","call_id":"call1","output":"result"}],
                "tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}]}
                """), "same-model");
        for (String endpoint : new String[]{"/chat/completions", "/responses", "/v1/messages"}) {
            var wire = CopilotRequestEncoder.encode(request, endpoint);
            assertEquals("same-model", wire.path("model").asString());
            assertTrue(wire.path("stream").asBoolean());
            assertTrue(wire.toString().contains("call1"));
            assertTrue(wire.toString().contains("result"));
            assertFalse(wire.toString().contains("You are Claude Code"));
        }
    }
}
