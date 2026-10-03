package com.aiproxy.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Account catalog facts only. Zero limits and absent capabilities mean unknown. */
public record ModelMetadata(
        int maxInputTokens,
        int maxOutputTokens,
        List<String> inputModalities,
        Map<String, Boolean> capabilities,
        List<String> reasoningEfforts,
        List<String> upstreamEndpoints,
        String source,
        Instant fetchedAt,
        String authProfile
) {
    public ModelMetadata {
        if (maxInputTokens < 0 || maxOutputTokens < 0) throw new IllegalArgumentException("Negative token limit");
        inputModalities = List.copyOf(inputModalities);
        capabilities = Map.copyOf(capabilities);
        reasoningEfforts = List.copyOf(reasoningEfforts);
        upstreamEndpoints = List.copyOf(upstreamEndpoints);
    }

    public static ModelMetadata unknown(String source) {
        return new ModelMetadata(0, 0, List.of(), Map.of(), List.of(), List.of(), source, null, null);
    }
}
