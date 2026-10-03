package com.aiproxy.protocol.shared;

import com.aiproxy.provider.spi.BlockType;
import com.aiproxy.provider.spi.CompletionEvent;
import com.aiproxy.provider.spi.FinishReason;
import com.aiproxy.provider.spi.ProviderError;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Per-stream event bookkeeping shared by the Chat and Responses wire decoders. */
public final class CompletionStreamEvents {
    private final String fallbackModel;
    private final String malformedStreamMessage;
    private final Map<String, Integer> blocks = new LinkedHashMap<>();
    private final Set<Integer> closed = new HashSet<>(), hasData = new HashSet<>();
    private boolean started, terminal, toolCalls;

    public CompletionStreamEvents(String fallbackModel, String malformedStreamMessage) {
        this.fallbackModel = Objects.requireNonNull(fallbackModel, "fallbackModel");
        this.malformedStreamMessage = Objects.requireNonNull(malformedStreamMessage, "malformedStreamMessage");
    }
    public boolean started() { return started; }
    public boolean terminal() { return terminal; }
    public boolean hasToolCalls() { return toolCalls; }
    public boolean hasBlock(String key) { return blocks.containsKey(key); }
    public Integer blockIndex(String key) { return blocks.get(key); }
    public boolean hasData(int index) { return hasData.contains(index); }
    public boolean recordData(int index) { return hasData.add(index); }
    public void closeBlocks(String prefix, List<CompletionEvent> out) {
        blocks.forEach((key, index) -> {
            if (key.startsWith(prefix) && closed.add(index)) out.add(new CompletionEvent.BlockFinished(index));
        });
    }

    public void start(JsonNode node, List<CompletionEvent> out) {
        if (started) return;
        started = true;
        out.add(new CompletionEvent.Started(node.path("id").asString(fallbackModel + "-" + UUID.randomUUID()),
                node.path("model").asString(fallbackModel), Math.max(0, node.path("created_at").asLong(node.path("created").asLong(System.currentTimeMillis() / 1000)))));
    }

    public int block(String key, BlockType type, String id, String name, List<CompletionEvent> out) {
        Integer existing = blocks.get(key);
        if (existing != null) return existing;
        toolCalls |= type == BlockType.TOOL_CALL;
        int index = blocks.size(); blocks.put(key, index);
        out.add(new CompletionEvent.BlockStarted(index, type, id, name)); return index;
    }

    public void text(String key, BlockType type, String text, List<CompletionEvent> out) {
        if (text.isEmpty()) return;
        int index = block(key, type, null, null, out); hasData.add(index);
        if (type == BlockType.REASONING) out.add(new CompletionEvent.ReasoningDelta(index, text));
        else if (type == BlockType.REFUSAL) out.add(new CompletionEvent.RefusalDelta(index, text));
        else out.add(new CompletionEvent.TextDelta(index, text));
    }

    public void finish(FinishReason reason, List<CompletionEvent> out) {
        for (int index : blocks.values()) if (closed.add(index)) out.add(new CompletionEvent.BlockFinished(index));
        terminal = true; out.add(new CompletionEvent.Finished(reason));
    }

    public void fail(List<CompletionEvent> out) {
        if (!terminal) out.add(new CompletionEvent.Error(ProviderError.of(ProviderError.Kind.PROTOCOL,
                malformedStreamMessage)));
        terminal = true;
    }
}
