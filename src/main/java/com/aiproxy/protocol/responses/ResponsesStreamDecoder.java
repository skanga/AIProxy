package com.aiproxy.protocol.responses;

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

/** Incremental Responses wire decoder; owns no transport resources. */
public final class ResponsesStreamDecoder implements CompletionStreamDecoder {
    private final CompletionStreamEvents events;
    private final IncrementalSseFramer framer = new IncrementalSseFramer(4 * 1024 * 1024);

    public ResponsesStreamDecoder() {
        this("openai", "Upstream returned a malformed, failed, or incomplete OpenAI stream");
    }

    public ResponsesStreamDecoder(String fallbackModel, String malformedStreamMessage) {
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
            events.fail(out); return;
        }
        JsonNode node;
        try { node = Json.MAPPER.readTree(frame.data()); }
        catch (Exception error) { events.fail(out); return; }
        if (node == null || !node.isObject() || node.has("error")) { events.fail(out); return; }
        response(node, out);
    }

    private void response(JsonNode node, List<CompletionEvent> out) {
        String type = node.path("type").asString();
        if (type.equals("response.created") || type.equals("response.in_progress")) { events.start(node.path("response"), out); return; }
        if (type.equals("response.failed") || type.equals("error")) { events.fail(out); return; }
        if (!events.started()) { events.fail(out); return; }
        int itemIndex = node.path("output_index").asInt();
        String key = itemIndex + "/" + node.path("content_index").asInt();
        switch (type) {
            case "response.output_item.added" -> {
                JsonNode item = node.path("item");
                if (item.path("type").asString().equals("function_call")) events.block(itemIndex + "/tool", BlockType.TOOL_CALL,
                        item.path("call_id").asString(), item.path("name").asString(), out);
            }
            case "response.output_text.delta" -> events.text(key + "/text", BlockType.TEXT, node.path("delta").asString(), out);
            case "response.refusal.delta" -> events.text(key + "/refusal", BlockType.REFUSAL, node.path("delta").asString(), out);
            case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> events.text(key + "/reasoning", BlockType.REASONING, node.path("delta").asString(), out);
            case "response.function_call_arguments.delta" -> {
                Integer index = events.blockIndex(itemIndex + "/tool");
                if (index == null) { events.fail(out); return; }
                events.recordData(index); out.add(new CompletionEvent.ToolCallArgumentsDelta(index, node.path("delta").asString()));
            }
            case "response.function_call_arguments.done" -> {
                Integer index = events.blockIndex(itemIndex + "/tool");
                if (index != null && !events.hasData(index)) {
                    events.recordData(index); out.add(new CompletionEvent.ToolCallArgumentsDelta(index, node.path("arguments").asString()));
                }
            }
            case "response.output_item.done" -> {
                snapshot(itemIndex, node.path("item"), out);
                events.closeBlocks(itemIndex + "/", out);
            }
            case "response.completed", "response.incomplete" -> {
                int index = 0;
                for (JsonNode item : node.path("response").path("output")) snapshot(index++, item, out);
                usage(node.path("response").path("usage"), out);
                FinishReason reason = type.equals("response.incomplete") ? FinishReason.LENGTH
                        : events.hasToolCalls() ? FinishReason.TOOL_CALLS : FinishReason.STOP;
                events.finish(reason, out);
            }
            default -> { }
        }
    }

    private void snapshot(int itemIndex, JsonNode item, List<CompletionEvent> out) {
        if (item.path("type").asString().equals("function_call")) {
            int index = events.block(itemIndex + "/tool", BlockType.TOOL_CALL, item.path("call_id").asString(), item.path("name").asString(), out);
            if (events.recordData(index)) out.add(new CompletionEvent.ToolCallArgumentsDelta(index, item.path("arguments").asString()));
        } else if (item.path("type").asString().equals("message")) {
            int partIndex = 0;
            for (JsonNode part : item.path("content")) {
                boolean refusal = part.path("type").asString().equals("refusal");
                String key = itemIndex + "/" + partIndex++ + (refusal ? "/refusal" : "/text");
                if (!events.hasBlock(key)) events.text(key, refusal ? BlockType.REFUSAL : BlockType.TEXT,
                        part.path(refusal ? "refusal" : "text").asString(), out);
            }
        }
    }

    private void usage(JsonNode node, List<CompletionEvent> out) {
        if (!node.isObject()) return;
        out.add(new CompletionEvent.UsageSnapshot(node.path("input_tokens").asLong(),
                node.path("output_tokens").asLong(), 0,
                node.path("input_tokens_details").path("cached_tokens").asLong()));
    }
}
