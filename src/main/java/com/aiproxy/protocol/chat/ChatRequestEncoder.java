package com.aiproxy.protocol.chat;

import com.aiproxy.provider.spi.ChatRequest;
import com.aiproxy.provider.spi.ChatRequestValidation;
import com.aiproxy.util.Json;
import java.util.Base64;
import java.util.Locale;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Encodes normalized requests into the Chat Completions wire format. */
public final class ChatRequestEncoder {
    private ChatRequestEncoder() {}

    public static ObjectNode encode(ChatRequest request) {
        ChatRequestValidation.validateToolChoice(request);
        ObjectNode root = Json.MAPPER.createObjectNode().put("model", request.model()).put("stream", request.stream());
        root.put("max_completion_tokens", request.maxOutputTokens());
        if (request.temperature() != null) root.put("temperature", request.temperature());
        if (request.topP() != null) root.put("top_p", request.topP());
        if (!request.stopSequences().isEmpty()) request.stopSequences().forEach(root.putArray("stop")::add);
        if (request.reasoningEffort() != null) root.put("reasoning_effort", request.reasoningEffort());
        ArrayNode messages = root.putArray("messages");
        for (var message : request.messages()) {
            ObjectNode encoded = Json.MAPPER.createObjectNode().put("role", message.role().name().toLowerCase(Locale.ROOT));
            ArrayNode content = encoded.putArray("content");
            for (var part : message.content()) {
                if (part instanceof ChatRequest.Text text) {
                    content.addObject().put("type", "text").put("text", text.text());
                } else if (part instanceof ChatRequest.Image image) {
                    String data = "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.data());
                    content.addObject().put("type", "image_url").putObject("image_url").put("url", data);
                } else if (part instanceof ChatRequest.ToolCall call) {
                    ArrayNode calls = encoded.has("tool_calls") ? (ArrayNode) encoded.get("tool_calls") : encoded.putArray("tool_calls");
                    calls.addObject().put("id", call.id()).put("type", "function").putObject("function")
                            .put("name", call.name()).put("arguments", call.argumentsJson());
                } else if (part instanceof ChatRequest.ToolResult result) {
                    messages.addObject().put("role", "tool").put("tool_call_id", result.toolCallId()).put("content", result.output());
                } else if (part instanceof ChatRequest.Reasoning reasoning) {
                    if (!reasoning.signature().isEmpty() || reasoning.redactedData() != null) {
                        throw new IllegalArgumentException("Provider-specific reasoning state cannot be translated to this OpenAI protocol");
                    }
                    encoded.put("reasoning_content", reasoning.text());
                }
            }
            if (!content.isEmpty() || encoded.has("tool_calls") || encoded.has("reasoning_content")) messages.add(encoded);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (var tool : request.tools()) {
                tools.addObject().put("type", "function").putObject("function")
                        .put("name", tool.name()).put("description", tool.description()).set("parameters", tool.inputSchema());
            }
            if (request.toolChoice() instanceof ChatRequest.ToolChoice.Named named) {
                root.putObject("tool_choice").put("type", "function").putObject("function").put("name", named.name());
            } else root.put("tool_choice", request.toolChoice() instanceof ChatRequest.ToolChoice.Required ? "required"
                    : request.toolChoice() instanceof ChatRequest.ToolChoice.None ? "none" : "auto");
        }
        return root;
    }
}
