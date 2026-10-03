package com.aiproxy.protocol.chat;

import com.aiproxy.provider.spi.ChatRequest;
import com.aiproxy.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatRequestDecoderTest {

    private final ChatRequestDecoder adapter = new ChatRequestDecoder();

    @Test void preservesDeveloperRoleAndJoinsToolTextParts() throws Exception {
        var request = adapter.decode(Json.MAPPER.readTree("""
                {"messages":[
                  {"role":"developer","content":"Be concise"},
                  {"role":"tool","tool_call_id":"c","is_error":true,
                   "content":[{"type":"text","text":"first"},{"type":"text","text":"second"}]}
                ],"max_tokens":10,"max_completion_tokens":20,"reasoning_effort":"HIGH",
                "stop":"END","top_p":0.8,"stream":true}
                """), "model");
        assertEquals(ChatRequest.Role.DEVELOPER, request.messages().getFirst().role());
        var result = assertInstanceOf(ChatRequest.ToolResult.class, request.messages().get(1).content().getFirst());
        assertEquals("firstsecond", result.output());
        assertTrue(result.error());
        assertEquals(20, request.maxOutputTokens());
        assertEquals("high", request.reasoningEffort());
        assertEquals(0.8, request.topP());
        assertTrue(request.stream());
    }

    @Test
    void adaptsTextToolsChoiceAndResolvedModel() throws Exception {
        ChatRequest request = adapter.decode(Json.MAPPER.readTree("""
                {
                  "model":"anthropic/claude-alias",
                  "messages":[
                    {"role":"system","content":"Be concise"},
                    {"role":"user","content":"Weather?"},
                    {"role":"assistant","content":null,"tool_calls":[{
                      "id":"call_1","type":"function",
                      "function":{"name":"weather","arguments":"{\\\"city\\\":\\\"LA\\\"}"}
                    }]},
                    {"role":"tool","tool_call_id":"call_1","content":"sunny"}
                  ],
                  "tools":[{"type":"function","function":{"name":"weather",
                    "description":"Lookup weather","parameters":{"type":"object"}}}],
                  "tool_choice":{"type":"function","function":{"name":"weather"}},
                  "max_completion_tokens":512,
                  "temperature":0.2,
                  "stop":["END"]
                }
                """), "claude-sonnet-4-5");

        assertEquals("claude-sonnet-4-5", request.model());
        assertEquals(4, request.messages().size());
        assertEquals(512, request.maxOutputTokens());
        assertEquals(0.2, request.temperature());
        assertInstanceOf(ChatRequest.ToolChoice.Named.class, request.toolChoice());
        assertInstanceOf(ChatRequest.ToolCall.class, request.messages().get(2).content().getFirst());
        assertInstanceOf(ChatRequest.ToolResult.class, request.messages().get(3).content().getFirst());
    }

    @Test
    void acceptsInlineImageAndRejectsRemoteImage() throws Exception {
        ChatRequest request = adapter.decode(Json.MAPPER.readTree("""
                {"messages":[{"role":"user","content":[
                  {"type":"text","text":"describe"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}}
                ]}]}
                """), "claude-sonnet-4-5");
        assertInstanceOf(ChatRequest.Image.class, request.messages().getFirst().content().get(1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                adapter.decode(Json.MAPPER.readTree("""
                    {"messages":[{"role":"user","content":[
                      {"type":"image_url","image_url":{"url":"https://example.test/a.png"}}
                    ]}]}
                    """), "claude-sonnet-4-5"));
        assertTrue(error.getMessage().contains("data URL"));
    }

    @Test
    void rejectsFractionalOutputTokenLimit() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                adapter.decode(Json.MAPPER.readTree("""
                    {"max_tokens":1.5,
                     "messages":[{"role":"user","content":"hello"}]}
                    """), "claude-sonnet-4-5"));
        assertTrue(error.getMessage().contains("positive integer"));
    }
}
