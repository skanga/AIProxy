package com.aiproxy.protocol.chat;

import com.aiproxy.protocol.shared.CompletionStreamEvents;
import com.aiproxy.provider.spi.BlockType;
import com.aiproxy.provider.spi.CompletionEvent;
import com.aiproxy.provider.spi.CompletionStreamDecoder;
import com.aiproxy.provider.spi.FinishReason;
import com.aiproxy.sse.IncrementalSseFramer;
import com.aiproxy.util.Json;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Incremental Chat Completions wire decoder; owns no transport resources. */
public final class ChatStreamDecoder implements CompletionStreamDecoder {
    private final CompletionStreamEvents events;
    private final IncrementalSseFramer framer = new IncrementalSseFramer(4 * 1024 * 1024);
    private FinishReason reason;
    public ChatStreamDecoder() {
        this("openai", "Upstream returned a malformed, failed, or incomplete OpenAI stream");
    }

    public ChatStreamDecoder(String fallbackModel, String malformedStreamMessage) {
        events = new CompletionStreamEvents(fallbackModel, malformedStreamMessage);
    }

    @Override
    public List<CompletionEvent> feed(byte[] bytes) {
        if (events.terminal()) return List.of();
        List<CompletionEvent> out = new ArrayList<>();
        try { framer.feed(bytes, frame -> consume(frame, out)); }
        catch (RuntimeException error) { events.fail(out); }
        return List.copyOf(out);
    }

    @Override
    public List<CompletionEvent> end() {
        if (events.terminal()) return List.of();
        List<CompletionEvent> out = new ArrayList<>(); events.fail(out); return out;
    }

    private void consume(IncrementalSseFramer.Event frame, List<CompletionEvent> out) {
        if (events.terminal() || frame.data().isEmpty()) return;
        if (frame.data().equals("[DONE]")) {
            if (!events.started() || reason == null) { events.fail(out); return; }
            events.finish(reason, out); return;
        }
        JsonNode node;
        try { node = Json.MAPPER.readTree(frame.data()); }
        catch (Exception error) { events.fail(out); return; }
        if (node == null || !node.isObject() || node.has("error")) { events.fail(out); return; }
        chat(node, out);
    }

    private void chat(JsonNode node, List<CompletionEvent> out) {
        if (!events.started() && node.path("choices").isEmpty() && node.has("prompt_filter_results")) return;
        events.start(node, out);
        for (JsonNode choice : node.path("choices")) {
            if (choice.path("index").asInt() != 0) { events.fail(out); return; }
            JsonNode delta = choice.path("delta");
            events.text("text", BlockType.TEXT, delta.path("content").asString(""), out);
            events.text("reasoning", BlockType.REASONING, delta.path("reasoning_content").asString(""), out);
            events.text("refusal", BlockType.REFUSAL, delta.path("refusal").asString(""), out);
            for (JsonNode call : delta.path("tool_calls")) {
                String key = "tool/" + call.path("index").asInt();
                int index = events.block(key, BlockType.TOOL_CALL, call.path("id").asString(null), call.path("function").path("name").asString(null), out);
                String args = call.path("function").path("arguments").asString("");
                if (!args.isEmpty()) { events.recordData(index); out.add(new CompletionEvent.ToolCallArgumentsDelta(index, args)); }
            }
            if (choice.hasNonNull("finish_reason")) reason = switch (choice.path("finish_reason").asString()) {
                case "stop" -> FinishReason.STOP;
                case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
                case "length" -> FinishReason.LENGTH;
                case "content_filter" -> FinishReason.CONTENT_FILTER;
                default -> throw new IllegalArgumentException("Unknown finish reason");
            };
        }
        if (node.path("usage").isObject()) usage(node.path("usage"), out);
    }

    private void usage(JsonNode node, List<CompletionEvent> out) {
        if (!node.isObject()) return;
        out.add(new CompletionEvent.UsageSnapshot(node.path("prompt_tokens").asLong(),
                node.path("completion_tokens").asLong(), 0,
                node.path("prompt_tokens_details").path("cached_tokens").asLong()));
    }
}
