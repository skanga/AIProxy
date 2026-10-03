package com.aiproxy.protocol;

import com.aiproxy.protocol.chat.ChatCompletionEncoder;
import com.aiproxy.protocol.chat.ChatRequestEncoder;
import com.aiproxy.protocol.chat.ChatStreamDecoder;
import com.aiproxy.protocol.responses.ResponsesRequestEncoder;
import com.aiproxy.protocol.responses.ResponsesStreamDecoder;
import com.aiproxy.protocol.chat.ChatRequestDecoder;
import com.aiproxy.provider.spi.CompletionEvent;
import com.aiproxy.util.Json;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolCodecsTest {
    @Test void imageDecodersApplyTheSameNeutralValidation() throws Exception {
        for (String url : java.util.List.of("https://example.com/image.png", "data:image/png;extra;base64,YQ==",
                "data:image/png,YQ==", "data:image/png;base64,%%%")) {
            var chat = Json.MAPPER.createObjectNode();
            chat.putArray("messages").addObject().put("role", "user").putArray("content")
                    .addObject().put("type", "image_url").putObject("image_url").put("url", url);
            var responses = Json.MAPPER.createObjectNode();
            responses.putArray("input").addObject().put("role", "user").putArray("content")
                    .addObject().put("type", "input_image").put("image_url", url);
            var chatError = assertThrows(IllegalArgumentException.class, () -> new ChatRequestDecoder().decode(chat, "model"));
            var responsesError = assertThrows(IllegalArgumentException.class,
                    () -> new com.aiproxy.protocol.responses.ResponsesRequestDecoder().decode(responses, "model"));
            assertEquals(chatError.getMessage(), responsesError.getMessage());
            assertFalse(chatError.getMessage().contains("Claude"));
        }
    }

    @Test void malformedStreamsFailOnceAndIgnoreLaterInput() {
        for (var decoder : java.util.List.of(new ChatStreamDecoder(), new ResponsesStreamDecoder())) {
            var events = decoder.feed("data: {broken}\n\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(1, events.size());
            assertInstanceOf(CompletionEvent.Error.class, events.getFirst());
            assertTrue(decoder.end().isEmpty());
            assertTrue(decoder.feed("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
        }
    }

    @Test void responsesToolSnapshotsDoNotDuplicateArgumentsOrBlockClosure() {
        String wire = """
                data: {"type":"response.created","response":{"id":"resp_1","model":"m"}}

                data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"lookup"}}

                data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}

                data: {"type":"response.function_call_arguments.done","output_index":0,"arguments":"{}"}

                data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"lookup","arguments":"{}"}}

                data: {"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"c1","name":"lookup","arguments":"{}"}]}}

                """;
        var decoder = new ResponsesStreamDecoder();
        var events = new java.util.ArrayList<CompletionEvent>();
        for (byte value : wire.getBytes(StandardCharsets.UTF_8)) events.addAll(decoder.feed(new byte[]{value}));
        assertEquals(1, events.stream().filter(CompletionEvent.ToolCallArgumentsDelta.class::isInstance).count());
        assertEquals(1, events.stream().filter(CompletionEvent.BlockFinished.class::isInstance).count());
        var finished = assertInstanceOf(CompletionEvent.Finished.class, events.getLast());
        assertEquals(com.aiproxy.provider.spi.FinishReason.TOOL_CALLS, finished.reason());
        assertTrue(decoder.end().isEmpty());
    }

    @Test void respectsRequestedStreamingWithoutImposingProviderOptions() throws Exception {
        for (boolean stream : new boolean[]{false, true}) {
            var request = new ChatRequestDecoder().decode(Json.MAPPER.readTree("""
                    {"messages":[{"role":"user","content":"hi"}],"stream":%s}
                    """.formatted(stream)), "any-model");
            for (var wire : java.util.List.of(ChatRequestEncoder.encode(request), ResponsesRequestEncoder.encode(request))) {
                assertEquals("any-model", wire.path("model").asString());
                assertEquals(stream, wire.path("stream").asBoolean());
                assertFalse(wire.has("store"));
                assertFalse(wire.has("stream_options"));
            }
        }
    }

    @Test void recoversFinalResponsesSnapshotWithoutDuplicatingStreamedText() {
        String start = "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_one\",\"model\":\"test\"}}\n\n";
        String done = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}}\n\n";
        for (boolean delta : new boolean[]{false, true}) {
            var encoder = new ChatCompletionEncoder("test");
            String wire = start + (delta ? "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"hello\"}\n\n" : "") + done;
            new ResponsesStreamDecoder().feed(wire.getBytes(StandardCharsets.UTF_8)).forEach(encoder::accept);
            assertEquals("hello", encoder.completion().at("/choices/0/message/content").asString());
        }
    }
    @Test void preservesTextBeforeToolCallAndRejectsUndeclaredNamedTool() throws Exception {
        var request = new ChatRequestDecoder().decode(Json.MAPPER.readTree("""
                {"messages":[{"role":"assistant","content":"I will look it up",
                "tool_calls":[{"id":"call1","function":{"name":"lookup","arguments":"{}"}}]}]}
                """), "test");
        var wire = ResponsesRequestEncoder.encode(request);
        assertEquals("message", wire.path("input").get(0).path("type").asString());
        assertEquals("function_call", wire.path("input").get(1).path("type").asString());
        var invalid = new ChatRequestDecoder().decode(Json.MAPPER.readTree("""
                {"messages":[{"role":"user","content":"hi"}],"tool_choice":{"type":"function","function":{"name":"missing"}}}
                """), "test");
        assertThrows(IllegalArgumentException.class, () -> ChatRequestEncoder.encode(invalid));
    }
    @Test void ignoresLeadingPromptFilterFrameWithoutACompletionId() throws Exception {
        var decoder = new ChatStreamDecoder();
        var events = decoder.feed(("data: {\"id\":\"\",\"model\":\"\",\"choices\":[],\"prompt_filter_results\":[]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"model\":\"test\",\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(events.stream().anyMatch(e -> e instanceof com.aiproxy.provider.spi.CompletionEvent.Finished));
        assertFalse(events.stream().anyMatch(e -> e instanceof com.aiproxy.provider.spi.CompletionEvent.Error));
    }
    @Test void decodesFragmentedChatToolArgumentsAndLateUsage() {
        String sse = """
                data: {"id":"c1","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call1","function":{"name":"lookup","arguments":"{\\\"q\\\":"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":"tool_calls"}]}

                data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3}}

                data: [DONE]

                """;
        var decoder = new ChatStreamDecoder();
        var encoder = new ChatCompletionEncoder("m");
        for (byte b : sse.getBytes(StandardCharsets.UTF_8)) decoder.feed(new byte[]{b}).forEach(encoder::accept);
        decoder.end().forEach(encoder::accept);
        assertTrue(encoder.isFinished());
        assertEquals(10, encoder.completion().path("usage").path("total_tokens").asInt());
        assertEquals("{\"q\":1}", encoder.completion().path("choices").get(0).path("message").path("tool_calls").get(0).path("function").path("arguments").asString());
    }
    @Test void translatesResponsesEventsAndRejectsTruncation() {
        var decoder = new ResponsesStreamDecoder();
        var encoder = new ChatCompletionEncoder("m");
        String sse = """
                event: response.created
                data: {"type":"response.created","response":{"id":"resp_1","model":"m","created_at":1}}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"hello"}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":2,"output_tokens":1}}}

                """;
        decoder.feed(sse.getBytes(StandardCharsets.UTF_8)).forEach(encoder::accept);
        assertEquals("hello", encoder.completion().path("choices").get(0).path("message").path("content").asString());
        assertTrue(new ChatStreamDecoder().end().stream().anyMatch(e -> e instanceof CompletionEvent.Error));
    }
}
