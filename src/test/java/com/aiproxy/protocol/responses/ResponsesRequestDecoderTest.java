package com.aiproxy.protocol.responses;

import com.aiproxy.provider.spi.ChatRequest;
import com.aiproxy.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponsesRequestDecoderTest {
    private final ResponsesRequestDecoder adapter = new ResponsesRequestDecoder();

    @Test void preservesReasoningSummarySignatureAndStructuredToolOutput() throws Exception {
        var request = adapter.decode(Json.MAPPER.readTree("""
                {"input":[
                  {"type":"reasoning","summary":[{"text":"first"},{"text":"second"}],
                   "content":[{"text":"third"}],"reasoning_signature":"signed"},
                  {"type":"function_call_output","call_id":"c","output":{"ok":true},"is_error":true}
                ],"reasoning":{"effort":"HIGH"},"temperature":0.3,"top_p":0.8,"stop":["END"],"stream":true}
                """), "model");
        var reasoning = assertInstanceOf(ChatRequest.Reasoning.class, request.messages().getFirst().content().getFirst());
        assertEquals("first\nsecond\nthird", reasoning.text());
        assertEquals("signed", reasoning.signature());
        var result = assertInstanceOf(ChatRequest.ToolResult.class, request.messages().get(1).content().getFirst());
        assertEquals("{\"ok\":true}", result.output());
        assertTrue(result.error());
        assertEquals("high", request.reasoningEffort());
        assertEquals(0.3, request.temperature());
        assertEquals(0.8, request.topP());
        assertTrue(request.stream());
    }

    @Test
    void adaptsInstructionsMessagesToolsAndControls() throws Exception {
        ChatRequest request = adapter.decode(Json.MAPPER.readTree("""
                {
                  "model":"anthropic/sonnet",
                  "instructions":"Be concise",
                  "input":[
                    {"type":"message","role":"user","content":[
                      {"type":"input_text","text":"Weather?"}]},
                    {"type":"function_call","call_id":"call_1","name":"weather",
                     "arguments":"{\\\"city\\\":\\\"LA\\\"}"},
                    {"type":"function_call_output","call_id":"call_1","output":"sunny"}
                  ],
                  "tools":[{"type":"function","name":"weather","description":"Lookup",
                    "parameters":{"type":"object"}}],
                  "tool_choice":{"type":"function","name":"weather"},
                  "max_output_tokens":321,
                  "reasoning":{"effort":"high"}
                }
                """), "claude-sonnet-4-5");

        assertEquals("claude-sonnet-4-5", request.model());
        assertEquals(4, request.messages().size());
        assertEquals(ChatRequest.Role.SYSTEM, request.messages().getFirst().role());
        assertInstanceOf(ChatRequest.ToolCall.class, request.messages().get(2).content().getFirst());
        assertInstanceOf(ChatRequest.ToolResult.class, request.messages().get(3).content().getFirst());
        assertInstanceOf(ChatRequest.ToolChoice.Named.class, request.toolChoice());
        assertEquals(321, request.maxOutputTokens());
        assertEquals("high", request.reasoningEffort());
    }

    @Test
    void adaptsStringInputAndInlineImage() throws Exception {
        ChatRequest text = adapter.decode(Json.MAPPER.readTree("""
                {"input":"hello"}
                """), "claude-sonnet-4-5");
        assertEquals("hello", ((ChatRequest.Text)
                text.messages().getFirst().content().getFirst()).text());

        ChatRequest image = adapter.decode(Json.MAPPER.readTree("""
                {"input":[{"type":"message","role":"user","content":[
                  {"type":"input_image","image_url":"data:image/png;base64,AQID"}]}]}
                """), "claude-sonnet-4-5");
        assertInstanceOf(ChatRequest.Image.class,
                image.messages().getFirst().content().getFirst());
    }

    @Test
    void rejectsUnsupportedItemsAndRemoteImages() throws Exception {
        IllegalArgumentException reference = assertThrows(IllegalArgumentException.class, () ->
                adapter.decode(Json.MAPPER.readTree("""
                    {"input":[{"type":"item_reference","id":"item_1"}]}
                    """), "claude-sonnet-4-5"));
        assertTrue(reference.getMessage().contains("item_reference"));

        IllegalArgumentException image = assertThrows(IllegalArgumentException.class, () ->
                adapter.decode(Json.MAPPER.readTree("""
                    {"input":[{"type":"message","role":"user","content":[
                      {"type":"input_image","image_url":"https://example.test/a.png"}]}]}
                    """), "claude-sonnet-4-5"));
        assertTrue(image.getMessage().contains("data URL"));
    }
}
