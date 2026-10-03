package com.aiproxy.protocol.messages;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Syntax and size limits for the Messages protocol beta header. */
public final class MessagesBetaHeaders {
    private static final int MAX_BETAS = 32;
    private static final Pattern BETA = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private MessagesBetaHeaders() {}

    public static List<String> parse(String betaHeader) {
        LinkedHashSet<String> betas = new LinkedHashSet<>();
        if (betaHeader != null && !betaHeader.isBlank()) {
            for (String raw : betaHeader.split(",")) {
                String beta = raw.strip();
                if (!BETA.matcher(beta).matches()) {
                    throw new IllegalArgumentException("Invalid anthropic-beta value");
                }
                betas.add(beta);
                if (betas.size() > MAX_BETAS) {
                    throw new IllegalArgumentException("Too many anthropic-beta values");
                }
            }
        }
        return List.copyOf(betas);
    }
}
