package com.aiproxy.protocol.messages;

import com.aiproxy.provider.spi.ChatRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesRequestEncoderTest {
    @Test
    void validatesEnabledToolsButOmitsDisabledDeclarations() throws Exception {
        var tool = new ChatRequest.ToolDefinition("read", "", JsonNodeFactory.instance.objectNode());
        var messages = List.of(message(ChatRequest.Role.USER, new ChatRequest.Text("hello")));
        var disabled = new ChatRequest("model", messages, List.of(tool, tool),
                new ChatRequest.ToolChoice.None(), 100, null, null, List.of(), null, false);
        assertFalse(new MessagesRequestEncoder().encode(disabled).has("tools"));
        var enabled = new ChatRequest("model", messages, List.of(tool, tool),
                new ChatRequest.ToolChoice.Auto(), 100, null, null, List.of(), null, false);
        var error = assertThrows(MessagesEncodingException.class, () -> new MessagesRequestEncoder().encode(enabled));
        assertEquals("Duplicate tool definition: read", error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> com.aiproxy.protocol.chat.ChatRequestEncoder.encode(disabled));
        assertThrows(IllegalArgumentException.class,
                () -> com.aiproxy.protocol.responses.ResponsesRequestEncoder.encode(disabled));
    }

    @Test
    void respectsStreamingAndDoesNotInjectProviderIdentity() throws Exception {
        for (boolean stream : new boolean[]{false, true}) {
            ChatRequest request = new ChatRequest("model",
                    List.of(message(ChatRequest.Role.SYSTEM, new ChatRequest.Text("user system")),
                            message(ChatRequest.Role.USER, new ChatRequest.Text("hello"))),
                    List.of(), new ChatRequest.ToolChoice.Auto(), 100, null, null, List.of(), null, stream);
            JsonNode body = new MessagesRequestEncoder().encode(request);
            assertEquals(stream, body.path("stream").asBoolean());
            assertEquals(1, body.path("system").size());
            assertEquals("user system", body.at("/system/0/text").asString());
        }
    }

    @Test
    void translatesEverySupportedFieldAndPreservesToolChronology() throws Exception {
        ChatRequest request = new ChatRequest(
                "claude-sonnet-4-5",
                List.of(
                        message(ChatRequest.Role.SYSTEM, new ChatRequest.Text("system")),
                        message(ChatRequest.Role.DEVELOPER, new ChatRequest.Text("developer")),
                        message(
                                ChatRequest.Role.USER,
                                new ChatRequest.Text("look"),
                                new ChatRequest.Image(
                                        "image/png", "png".getBytes(StandardCharsets.UTF_8))
                        ),
                        message(
                                ChatRequest.Role.ASSISTANT,
                                new ChatRequest.Reasoning("plan", "signed", null),
                                new ChatRequest.Text("checking"),
                                new ChatRequest.ToolCall("call-1", "read", "{\"path\":\"README.md\"}")
                        ),
                        message(
                                ChatRequest.Role.TOOL,
                                new ChatRequest.ToolResult("call-1", "contents", false)
                        ),
                        message(ChatRequest.Role.USER, new ChatRequest.Text("continue"))
                ),
                List.of(new ChatRequest.ToolDefinition(
                        "read",
                        "Read a file",
                        JsonNodeFactory.instance.objectNode().put("type", "object")
                )),
                new ChatRequest.ToolChoice.Named("read"),
                4096,
                0.25,
                0.9,
                List.of("STOP"),
                "high",
                false
        );

        JsonNode body = new MessagesRequestEncoder().encode(request);

        assertEquals("claude-sonnet-4-5", body.path("model").asString());
        assertEquals(4096, body.path("max_tokens").asInt());
        assertFalse(body.path("stream").asBoolean());
        assertEquals(0.25, body.path("temperature").asDouble());
        assertEquals(0.9, body.path("top_p").asDouble());
        assertEquals("STOP", body.path("stop_sequences").get(0).asString());
        assertEquals("adaptive", body.path("thinking").path("type").asString());
        assertEquals("high", body.path("output_config").path("effort").asString());

        assertEquals(2, body.path("system").size());
        assertEquals("system", body.path("system").get(0).path("text").asString());
        assertEquals("developer", body.path("system").get(1).path("text").asString());

        JsonNode messages = body.path("messages");
        assertEquals(List.of("user", "assistant", "user"), List.of(
                messages.get(0).path("role").asString(),
                messages.get(1).path("role").asString(),
                messages.get(2).path("role").asString()
        ));
        assertEquals("base64", messages.get(0).path("content").get(1)
                .path("source").path("type").asString());
        assertEquals("thinking", messages.get(1).path("content").get(0).path("type").asString());
        assertEquals("tool_use", messages.get(1).path("content").get(2).path("type").asString());
        assertEquals("README.md", messages.get(1).path("content").get(2)
                .path("input").path("path").asString());
        assertEquals("tool_result", messages.get(2).path("content").get(0).path("type").asString());
        assertEquals("continue", messages.get(2).path("content").get(1).path("text").asString());

        assertEquals("read", body.path("tools").get(0).path("name").asString());
        assertEquals("tool", body.path("tool_choice").path("type").asString());
        assertEquals("read", body.path("tool_choice").path("name").asString());
    }

    @Test
    void mergesAdjacentEffectiveRoles() throws Exception {
        ChatRequest request = request(List.of(
                message(ChatRequest.Role.USER, new ChatRequest.Text("one")),
                message(ChatRequest.Role.USER, new ChatRequest.Text("two")),
                message(ChatRequest.Role.ASSISTANT, new ChatRequest.Text("three"))
        ));

        JsonNode messages = new MessagesRequestEncoder()
                .encode(request).path("messages");

        assertEquals(2, messages.size());
        assertEquals(2, messages.get(0).path("content").size());
    }

    @Test
    void rejectsDuplicateOrOrphanToolIdsAndInvalidArguments() {
        MessagesRequestEncoder translator = new MessagesRequestEncoder();

        assertThrows(MessagesEncodingException.class, () -> translator.encode(request(List.of(
                message(
                        ChatRequest.Role.ASSISTANT,
                        new ChatRequest.ToolCall("same", "a", "{}"),
                        new ChatRequest.ToolCall("same", "b", "{}")
                )
        ))));
        assertThrows(MessagesEncodingException.class, () -> translator.encode(
                new ChatRequest(
                        "claude",
                        List.of(message(ChatRequest.Role.USER, new ChatRequest.Text("hello"))),
                        List.of(),
                        new ChatRequest.ToolChoice.Named("missing"),
                        100,
                        null,
                        null,
                        List.of(),
                        null,
                        true
                )
        ));
        assertThrows(MessagesEncodingException.class, () -> translator.encode(request(List.of(
                message(
                        ChatRequest.Role.TOOL,
                        new ChatRequest.ToolResult("missing", "output", false)
                )
        ))));
        assertThrows(MessagesEncodingException.class, () -> translator.encode(request(List.of(
                message(
                        ChatRequest.Role.ASSISTANT,
                        new ChatRequest.ToolCall("call", "read", "not-json")
                )
        ))));
    }

    @Test
    void rejectsUnsupportedImageMediaTypes() {
        ChatRequest request = request(List.of(message(
                ChatRequest.Role.USER,
                new ChatRequest.Image("image/svg+xml", new byte[]{1})
        )));

        MessagesEncodingException error = assertThrows(
                MessagesEncodingException.class,
                () -> new MessagesRequestEncoder().encode(request)
        );

        assertEquals(400, error.error().httpStatus());
        assertFalse(error.getMessage().contains("data"));
    }

    @Test
    void mapsRequiredAndAutoToolChoicesAndRejectsRequiredWithoutTools() throws Exception {
        ChatRequest.ToolDefinition tool = new ChatRequest.ToolDefinition(
                "read", "", JsonNodeFactory.instance.objectNode());
        ChatRequest required = new ChatRequest(
                "claude",
                List.of(message(ChatRequest.Role.USER, new ChatRequest.Text("hello"))),
                List.of(tool),
                new ChatRequest.ToolChoice.Required(),
                100,
                null,
                null,
                List.of(),
                null,
                true
        );
        JsonNode requiredBody = new MessagesRequestEncoder().encode(required);
        assertEquals("any", requiredBody.path("tool_choice").path("type").asString());

        ChatRequest invalid = new ChatRequest(
                "claude",
                required.messages(),
                List.of(),
                new ChatRequest.ToolChoice.Required(),
                100,
                null,
                null,
                List.of(),
                null,
                true
        );
        assertThrows(
                MessagesEncodingException.class,
                () -> new MessagesRequestEncoder().encode(invalid)
        );
    }

    private static ChatRequest request(List<ChatRequest.Message> messages) {
        return new ChatRequest(
                "claude-sonnet-4-5",
                messages,
                List.of(),
                new ChatRequest.ToolChoice.Auto(),
                1024,
                null,
                null,
                List.of(),
                null,
                true
        );
    }

    private static ChatRequest.Message message(
            ChatRequest.Role role, ChatRequest.Content... content) {
        return new ChatRequest.Message(role, List.of(content));
    }
}
