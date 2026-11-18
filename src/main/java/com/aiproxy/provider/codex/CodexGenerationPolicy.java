package com.aiproxy.provider.codex;

import com.aiproxy.provider.spi.GenerationParameters;
import tools.jackson.databind.JsonNode;

/** Restrictions of the supported Codex inference profiles, shared by execution and failover checks. */
final class CodexGenerationPolicy {
    private CodexGenerationPolicy() {}
    static void validate(JsonNode body, boolean responses) {
        GenerationParameters.validate(body, responses);
        if (GenerationParameters.tokenLimit(body, responses) != null) {
            String field = responses ? "max_output_tokens" : body.hasNonNull("max_completion_tokens")
                    ? "max_completion_tokens" : "max_tokens";
            throw GenerationParameters.unsupported(field, "Codex does not support an explicit output token limit.");
        }
    }
}
