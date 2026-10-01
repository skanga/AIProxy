package com.aiproxyoauth;

import com.aiproxyoauth.config.EffectiveConfig;
import com.aiproxyoauth.provider.ProviderId;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The single startup display. It accepts already-redacted status metadata, never credentials. */
public final class StartupRenderer {

    private StartupRenderer() {}

    public record ProviderStatus(String credentialSource, List<String> models, String modelSource,
                                 String modelDiagnostic, Check check) {
        public ProviderStatus(String credentialSource, List<String> models, String modelSource, Check check) {
            this(credentialSource, models, modelSource, null, check);
        }

        public ProviderStatus {
            models = models == null ? List.of() : List.copyOf(models);
            credentialSource = credentialSource == null ? "not available" : credentialSource;
            modelSource = normalizeModelSource(modelSource);
            check = check == null ? Check.skipped() : check;
        }

        public boolean hasModelWarning() {
            return models.isEmpty() || modelSource.equals("fallback") || modelSource.equals("stale cache")
                    || modelSource.equals("unavailable");
        }
    }

    public record Check(State state, String model, String diagnostic) {
        public enum State { OK, FAILED, SKIPPED }
        public static Check ok(String model) { return new Check(State.OK, model, null); }
        public static Check failed(String model, String diagnostic) { return new Check(State.FAILED, model, diagnostic); }
        public static Check skipped() { return new Check(State.SKIPPED, null, null); }
    }

    public static String render(EffectiveConfig config, Map<ProviderId, ProviderStatus> providerStatuses) {
        return render(config, providerStatuses, false);
    }

    public static String render(EffectiveConfig config, Map<ProviderId, ProviderStatus> providerStatuses, boolean diagnostics) {
        StringBuilder output = new StringBuilder();
        List<String> warnings = new ArrayList<>();
        output.append(diagnostics ? "AIProxyOauth 4.2 diagnostics\n\n" : "AIProxyOauth 4.2 started\n\n");
        output.append("Server\n");
        output.append(diagnostics ? "  Configured URL:  http://" : "  Listening:       http://")
                .append(config.server().host()).append(':').append(config.server().port()).append('\n');
        output.append("  Network access:  ").append(local(config.server().host()) ? "local only" : "network accessible").append('\n');
        output.append("  Client auth:     ").append(config.clientAuth().enabled() ? "enabled" : "disabled").append('\n');
        output.append("  CORS:            ").append(cors(config)).append('\n');
        output.append("  Request logging: ").append(config.logging().requests() ? "enabled" : "disabled").append('\n');
        output.append("  Startup check:   ").append(config.startup().check().name().toLowerCase(Locale.ROOT)).append("\n\n");
        output.append("Client APIs\n");
        output.append("  OpenAI-compatible:    /v1/chat/completions, /v1/responses, /v1/models\n");
        output.append("  Anthropic-compatible: /v1/messages\n\n");
        output.append("Routing\n");
        output.append("  Providers:        ").append(providerNames(providerStatuses, config.routing().providerOrder())).append('\n');
        output.append("  Default provider: ").append(config.routing().defaultProvider().wireName()).append("\n\n");
        output.append("Providers\n");
        for (ProviderId provider : config.routing().providerOrder()) {
            ProviderStatus status = providerStatuses.get(provider);
            if (status == null) continue;
            output.append("  ").append(switch (provider) { case COPILOT -> "Copilot"; case CODEX -> "Codex"; case ANTHROPIC -> "Anthropic"; }).append(":\n");
            output.append("    Auth:    source: ").append(safe(status.credentialSource())).append('\n');
            output.append("    Models:  ").append(status.models().size()).append(", ").append(status.modelSource());
            if (status.modelSource().equals("fallback")) output.append(" (built-in)");
            output.append('\n');
            if (status.hasModelWarning()) {
                String detail = status.modelDiagnostic();
                if (detail == null || detail.isBlank()) detail = status.models().isEmpty()
                        ? "No models available" : "Model discovery failed";
                warnings.add(provider.wireName() + " models (" + status.modelSource() + "): " + safe(detail));
            }
            if (!status.models().isEmpty()) {
                output.append("    IDs:     ").append(String.join(", ", status.models())).append('\n');
            }
            switch (status.check().state()) {
                case OK -> output.append("    Check:   OK using ").append(safe(status.check().model())).append('\n');
                case SKIPPED -> output.append("    Check:   skipped\n");
                case FAILED -> {
                    String warning = provider.wireName() + " check failed"
                            + (status.check().diagnostic() == null ? "" : ": " + safe(status.check().diagnostic()));
                    warnings.add(warning);
                    output.append("    Check:   failed using ").append(safe(status.check().model())).append('\n');
                }
            }
            output.append('\n');
        }
        if (!warnings.isEmpty()) {
            output.append("Warnings\n");
            warnings.forEach(warning -> output.append("  - ").append(warning).append('\n'));
            output.append('\n');
        }
        output.append(diagnostics ? "Diagnostics complete.\n" : warnings.isEmpty() ? "Ready.\n" : "Ready with warnings.\n");
        return output.toString();
    }

    private static String providerNames(Map<ProviderId, ProviderStatus> statuses, List<ProviderId> order) {
        return String.join(", ", order.stream().filter(statuses::containsKey).map(ProviderId::wireName).toList());
    }

    private static String cors(EffectiveConfig config) {
        if (config.cors().allowAny()) return "any origin";
        if (!config.cors().origins().isEmpty()) return String.join(", ", config.cors().origins());
        return "disabled";
    }

    private static boolean local(String host) {
        if (host == null) return true;
        String value = host.strip().toLowerCase(Locale.ROOT);
        return "localhost".equals(value) || "::1".equals(value) || value.startsWith("127.")
                || "0:0:0:0:0:0:0:1".equals(value);
    }

    private static String normalizeModelSource(String source) {
        if (source == null) return "unavailable";
        return switch (source.toLowerCase(Locale.ROOT)) {
            case "configured", "discovered", "cache", "stale cache", "fallback", "unavailable" -> source.toLowerCase(Locale.ROOT);
            default -> "unavailable";
        };
    }

    static String safe(String text) {
        if (text == null || text.isBlank()) return "unknown";
        // No truncation: diagnostics print in full and may wrap onto the next line.
        return text.replaceAll("(?i)bearer\\s+[^\\s,;]+", "Bearer <redacted>")
                .replaceAll("(?i)(token|secret|api[_ -]?key)\\s*[=:]\\s*[^\\s,;]+", "$1=<redacted>")
                .replaceAll("sk-proxy-[A-Za-z0-9_-]+", "<redacted>")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "<redacted-email>")
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ").strip();
    }
}
