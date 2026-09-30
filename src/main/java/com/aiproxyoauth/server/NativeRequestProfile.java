package com.aiproxyoauth.server;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.Set;
import static com.aiproxyoauth.util.Json.MAPPER;

/** Native plan grants have a stricter public Responses contract than the CLI backend. */
final class NativeRequestProfile {
    private static final Set<String> UNSUPPORTED = Set.of("background","conversation","max_output_tokens",
            "max_tool_calls","metadata","moderation","multi_agent","prompt","prompt_cache_retention",
            "safety_identifier","temperature","top_logprobs","top_p","truncation","user");
    private static final Set<String> CHAT_FIELDS = Set.of("model","messages","stream","stream_options",
            "tools","tool_choice","reasoning_effort","store");
    static String validate(JsonNode body, boolean chat) {
        for (var field : body.properties()) {
            if (UNSUPPORTED.contains(field.getKey()) || (chat && !CHAT_FIELDS.contains(field.getKey())))
                return "Native Codex does not support `" + field.getKey() + "`.";
        }
        if (body.has("store") && (!body.path("store").isBoolean() || body.path("store").asBoolean()))
            return "Native Codex requires `store: false`.";
        if (body.hasNonNull("previous_response_id") && (!body.path("previous_response_id").isString()
                || body.path("previous_response_id").asString().isBlank()))
            return "`previous_response_id` must be a nonempty string.";
        String tools = validateTools(body.get("tools"),chat);
        if (tools != null) return tools;
        JsonNode input = body.path(chat ? "messages" : "input");
        if (input.isArray()) for (JsonNode item : input) {
            if ("item_reference".equals(item.path("type").asString())
                    && (!item.path("id").isString() || item.path("id").asString().isBlank()))
                return "Native Codex item references require an ID from local history.";
            if (!chat && "system".equals(item.path("role").asString()))
                return "Native Codex requires developer messages or instructions instead of system input items.";
            if (chat && !Set.of("system","developer","user","assistant","tool").contains(item.path("role").asString()))
                return "Unsupported native chat message role.";
            if ("additional_tools".equals(item.path("type").asString())) {
                String nested = validateTools(item.get("tools"),false);
                if (nested != null) return nested;
            }
            for (JsonNode content : item.path("content")) {
                String type = content.path("type").asString();
                if (type.contains("audio") || type.contains("video")) return "Native Codex does not support audio/video input.";
                if (chat && !Set.of("text","image_url").contains(type)) return "Unsupported native chat content type: " + type;
            }
        }
        return null;
    }
    private static String validateTools(JsonNode tools, boolean chat) {
        if (tools == null) return null;
        if (!tools.isArray()) return "`tools` must be an array.";
        for (JsonNode tool : tools) {
            String type = tool.path("type").asString();
            if (chat ? !"function".equals(type) : !Set.of("function","custom","namespace","web_search","web_search_preview").contains(type))
                return "Native Codex does not support tool type `" + type + "`.";
            if (tool.path("defer_loading").asBoolean(false)) return "Native Codex does not support deferred tool search.";
            if ("namespace".equals(type)) {
                String error = validateTools(tool.get("tools"),false);
                if (error != null) return error;
            }
        }
        return null;
    }
    static ObjectNode prepare(ObjectNode body) {
        return prepare(body, 0);
    }
    static ObjectNode prepare(ObjectNode body, int historyItems) {
        body.put("store",false).put("stream",true);
        body.remove("previous_response_id"); // The handler has expanded or rejected every reference.
        if (!body.has("input")) body.putArray("input");
        for (JsonNode item : body.path("input")) {
            for (JsonNode content : item.path("content")) {
                if (content instanceof ObjectNode image && "input_image".equals(image.path("type").asString()) && image.has("url")) {
                    image.set("image_url",image.remove("url"));
                }
            }
        }
        ArrayNode functions = MAPPER.createArrayNode(), remaining = MAPPER.createArrayNode();
        for (JsonNode tool : body.path("tools")) {
            if (Set.of("function","custom").contains(tool.path("type").asString())) functions.add(tool);
            else remaining.add(tool);
        }
        if (!functions.isEmpty()) {
            ObjectNode additional = MAPPER.createObjectNode().put("type","additional_tools").put("role","developer");
            additional.set("tools",functions);
            ArrayNode input = MAPPER.createArrayNode();
            int index = 0;
            for (JsonNode item : body.path("input")) {
                if (index++ == historyItems) input.add(additional);
                input.add(item);
            }
            if (historyItems >= index) input.add(additional);
            body.set("input",input);
            if (remaining.isEmpty()) body.remove("tools"); else body.set("tools",remaining);
        }
        return body;
    }
}
