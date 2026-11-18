package com.aiproxy.protocol.chat;

import com.aiproxy.provider.spi.ChatRequest;
import com.aiproxy.provider.spi.GenerationParameters;
import com.aiproxy.util.Json;
import java.util.ArrayList;
import com.aiproxy.protocol.shared.InlineImageDecoder;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class ChatRequestDecoder {

    public ChatRequest decode(JsonNode root, String upstreamModel) {
        if (root == null || !root.isObject()) {
            throw invalid("Request body must be a JSON object");
        }
        GenerationParameters.validate(root, false);
        JsonNode messagesNode = root.get("messages");
        if (messagesNode == null || !messagesNode.isArray()) {
            throw invalid("`messages` must be an array");
        }
        List<ChatRequest.Message> messages = new ArrayList<>();
        for (JsonNode message : messagesNode) {
            messages.add(adaptMessage(message));
        }
        if (messages.isEmpty()) {
            throw invalid("`messages` cannot be empty");
        }
        List<ChatRequest.ToolDefinition> tools = adaptTools(root.path("tools"));
        return new ChatRequest(
                upstreamModel,
                messages,
                tools,
                adaptToolChoice(root.get("tool_choice")),
                GenerationParameters.tokenLimit(root, false),
                GenerationParameters.number(root, "temperature", 0, 2),
                GenerationParameters.number(root, "top_p", 0, 1),
                adaptStops(root.get("stop")),
                GenerationParameters.effort(root, false),
                root.path("stream").asBoolean(false)
        );
    }

    private ChatRequest.Message adaptMessage(JsonNode message) {
        if (message == null || !message.isObject()) throw invalid("Each message must be an object");
        ChatRequest.Role role = switch (message.path("role").asString()) {
            case "system" -> ChatRequest.Role.SYSTEM;
            case "developer" -> ChatRequest.Role.DEVELOPER;
            case "user" -> ChatRequest.Role.USER;
            case "assistant" -> ChatRequest.Role.ASSISTANT;
            case "tool" -> ChatRequest.Role.TOOL;
            default -> throw invalid("Unsupported message role");
        };
        List<ChatRequest.Content> content = new ArrayList<>();
        if (role == ChatRequest.Role.TOOL) {
            String callId = requiredText(message, "tool_call_id");
            content.add(new ChatRequest.ToolResult(callId, textContent(message.get("content")),
                    message.path("is_error").asBoolean(false)));
            return new ChatRequest.Message(role, content);
        }
        addContent(content, message.get("content"));
        if (role == ChatRequest.Role.ASSISTANT) {
            JsonNode calls = message.get("tool_calls");
            if (calls != null && !calls.isNull()) {
                if (!calls.isArray()) throw invalid("`tool_calls` must be an array");
                for (JsonNode call : calls) {
                    JsonNode function = call.path("function");
                    content.add(new ChatRequest.ToolCall(
                            requiredText(call, "id"),
                            requiredText(function, "name"),
                            requiredText(function, "arguments")
                    ));
                }
            }
        }
        return new ChatRequest.Message(role, content);
    }

    private void addContent(List<ChatRequest.Content> target, JsonNode content) {
        if (content == null || content.isNull()) return;
        if (content.isString()) {
            target.add(new ChatRequest.Text(content.asString()));
            return;
        }
        if (!content.isArray()) throw invalid("Message content must be text or an array");
        for (JsonNode part : content) {
            String type = part.path("type").asString();
            if ("text".equals(type)) {
                target.add(new ChatRequest.Text(requiredText(part, "text")));
            } else if ("image_url".equals(type)) {
                target.add(InlineImageDecoder.decode(part.path("image_url").path("url").asString()));
            } else {
                throw invalid("Unsupported message content type: " + type);
            }
        }
    }

    private List<ChatRequest.ToolDefinition> adaptTools(JsonNode toolsNode) {
        if (toolsNode == null || toolsNode.isMissingNode() || toolsNode.isNull()) return List.of();
        if (!toolsNode.isArray()) throw invalid("`tools` must be an array");
        List<ChatRequest.ToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            if (!"function".equals(tool.path("type").asString())) {
                throw invalid("Only function tools are supported");
            }
            JsonNode function = tool.path("function");
            JsonNode schema = function.get("parameters");
            if (schema == null || schema.isNull()) {
                ObjectNode empty = Json.MAPPER.createObjectNode();
                empty.put("type", "object");
                empty.set("properties", Json.MAPPER.createObjectNode());
                schema = empty;
            }
            tools.add(new ChatRequest.ToolDefinition(
                    requiredText(function, "name"),
                    function.path("description").asString(""), schema));
        }
        return List.copyOf(tools);
    }

    private ChatRequest.ToolChoice adaptToolChoice(JsonNode choice) {
        if (choice == null || choice.isNull()) return new ChatRequest.ToolChoice.Auto();
        if (choice.isString()) {
            return switch (choice.asString()) {
                case "auto" -> new ChatRequest.ToolChoice.Auto();
                case "none" -> new ChatRequest.ToolChoice.None();
                case "required" -> new ChatRequest.ToolChoice.Required();
                default -> throw invalid("Unsupported `tool_choice`");
            };
        }
        if (choice.isObject() && "function".equals(choice.path("type").asString())) {
            return new ChatRequest.ToolChoice.Named(requiredText(choice.path("function"), "name"));
        }
        throw invalid("Unsupported `tool_choice`");
    }

    private List<String> adaptStops(JsonNode stop) {
        if (stop == null || stop.isNull()) return List.of();
        if (stop.isString()) return List.of(stop.asString());
        if (!stop.isArray()) throw invalid("`stop` must be a string or an array of strings");
        List<String> stops = new ArrayList<>();
        stop.forEach(value -> {
            if (!value.isString()) throw invalid("Every stop sequence must be a string");
            stops.add(value.asString());
        });
        return List.copyOf(stops);
    }

    private String textContent(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isString()) return content.asString();
        if (!content.isArray()) throw invalid("Tool content must be text or an array");
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            if (!"text".equals(part.path("type").asString())) {
                throw invalid("Tool content may contain only text");
            }
            text.append(requiredText(part, "text"));
        }
        return text.toString();
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw invalid("`" + field + "` must be a non-empty string");
        }
        return value.asString();
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
