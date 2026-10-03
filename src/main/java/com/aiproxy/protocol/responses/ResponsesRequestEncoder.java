package com.aiproxy.protocol.responses;

import com.aiproxy.provider.spi.ChatRequest;
import com.aiproxy.provider.spi.ChatRequestValidation;
import com.aiproxy.util.Json;
import java.util.Base64;
import java.util.Locale;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Encodes normalized requests into the Responses wire format. */
public final class ResponsesRequestEncoder {
    private ResponsesRequestEncoder() {}

    public static ObjectNode encode(ChatRequest request) {
        ChatRequestValidation.validateToolChoice(request);
        ObjectNode root = Json.MAPPER.createObjectNode().put("model", request.model()).put("stream", request.stream());
        root.put("max_output_tokens", request.maxOutputTokens());
        if (request.temperature() != null) root.put("temperature", request.temperature());
        if (request.topP() != null) root.put("top_p", request.topP());
        if (!request.stopSequences().isEmpty()) throw new IllegalArgumentException("stop is unsupported by the OpenAI Responses protocol");
        if (request.reasoningEffort() != null) root.putObject("reasoning").put("effort", request.reasoningEffort());
        ArrayNode input = root.putArray("input");
        for (var message : request.messages()) {
            String role = message.role().name().toLowerCase(Locale.ROOT);
            ObjectNode encoded = Json.MAPPER.createObjectNode().put("role", role).put("type", "message");
            ArrayNode content = encoded.putArray("content");
            for (var part : message.content()) {
                if (!(part instanceof ChatRequest.Text) && !(part instanceof ChatRequest.Image) && !content.isEmpty()) {
                    input.add(encoded.deepCopy());
                    content.removeAll();
                }
                if (part instanceof ChatRequest.Text text) {
                    content.addObject().put("type", role.equals("assistant") ? "output_text" : "input_text").put("text", text.text());
                } else if (part instanceof ChatRequest.Image image) {
                    String data = "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.data());
                    content.addObject().put("type", "input_image").put("image_url", data);
                } else if (part instanceof ChatRequest.ToolCall call) {
                    input.addObject().put("type", "function_call").put("call_id", call.id()).put("name", call.name()).put("arguments", call.argumentsJson());
                } else if (part instanceof ChatRequest.ToolResult result) {
                    input.addObject().put("type", "function_call_output").put("call_id", result.toolCallId()).put("output", result.output());
                } else if (part instanceof ChatRequest.Reasoning reasoning) {
                    if (!reasoning.signature().isEmpty() || reasoning.redactedData() != null) {
                        throw new IllegalArgumentException("Provider-specific reasoning state cannot be translated to this OpenAI protocol");
                    }
                    input.addObject().put("type", "reasoning").putArray("summary")
                            .addObject().put("type", "summary_text").put("text", reasoning.text());
                }
            }
            if (!content.isEmpty()) input.add(encoded);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (var tool : request.tools()) {
                tools.addObject().put("type", "function").put("name", tool.name())
                        .put("description", tool.description()).set("parameters", tool.inputSchema());
            }
            if (request.toolChoice() instanceof ChatRequest.ToolChoice.Named named) {
                root.putObject("tool_choice").put("type", "function").put("name", named.name());
            } else root.put("tool_choice", request.toolChoice() instanceof ChatRequest.ToolChoice.Required ? "required"
                    : request.toolChoice() instanceof ChatRequest.ToolChoice.None ? "none" : "auto");
        }
        return root;
    }
}
