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

    public enum Support { UNKNOWN, SUPPORTED, UNSUPPORTED }

    public Support support(String capability) {
        Boolean value = capabilities.get(capability);
        return value == null ? Support.UNKNOWN : value ? Support.SUPPORTED : Support.UNSUPPORTED;
    }

    /** An absent effort list is not a denial. Parsers record explicit empty lists as unsupported. */
    public Support reasoningEffortSupport(String effort) {
        if (support("reasoning_effort") == Support.UNSUPPORTED) return Support.UNSUPPORTED;
        Support explicit = support("reasoning_effort." + effort);
        if (explicit != Support.UNKNOWN) return explicit;
        if (reasoningEfforts.contains(effort)) return Support.SUPPORTED;
        // Boolean-per-level catalogs can be partial; absence there is not an explicit denial.
        if (reasoningEfforts.isEmpty() || capabilities.keySet().stream().anyMatch(key -> key.startsWith("reasoning_effort."))) {
            return Support.UNKNOWN;
        }
        return Support.UNSUPPORTED;
    }

    public static ModelMetadata unknown(String source) {
        return new ModelMetadata(0, 0, List.of(), Map.of(), List.of(), List.of(), source, null, null);
    }
}
