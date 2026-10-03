package com.aiproxy.cli;

import com.aiproxy.config.EffectiveConfig;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.codex.auth.nativeoauth.CodexAuthSelection;
import com.aiproxy.provider.codex.auth.nativeoauth.NativeOAuth;
import java.nio.file.Path;
import java.util.Locale;

final class ConfigRenderer {
    private ConfigRenderer() {}
    static void printResolvedConfig(java.io.PrintWriter out, EffectiveConfig config) {
        CodexAuthSelection selectedAuth = CodexAuthSelection.select(config.codex());
        out.println("server.host: " + config.server().host() + source(config, "server.host"));
        out.println("server.port: " + config.server().port() + source(config, "server.port"));
        out.println("routing.provider: " + config.routing().provider().name().toLowerCase(Locale.ROOT) + source(config, "routing.provider"));
        out.println("routing.selected_providers: " + config.routing().selectedProviders().stream()
                .map(ProviderId::wireName).toList() + source(config, "routing.provider"));
        out.println("routing.default_provider: " + config.routing().defaultProvider().wireName() + source(config, "routing.default_provider"));
        out.println("routing.provider_order: " + config.routing().providerOrder() + source(config, "routing.provider_order"));
        out.println("routing.failover: " + config.routing().failover() + source(config, "routing.failover"));
        out.println("copilot.github_host: " + config.copilot().githubHost() + source(config, "copilot.github_host"));
        out.println("copilot.oauth_file: " + config.copilot().oauthFile() + source(config, "copilot.oauth_file"));
        out.println("copilot.token_file: " + displayPath(config.copilot().tokenFile()) + source(config, "copilot.token_file"));
        out.println("copilot.oauth_client_id: " + config.copilot().oauthClientId() + source(config, "copilot.oauth_client_id"));
        out.println("copilot.models: " + config.copilot().models() + source(config, "copilot.models"));
        out.println("copilot.environment_token: " + (config.copilot().environmentToken() == null ? "not set" : "<redacted>"));
        out.println("client_auth.keys_file: " + displayPath(config.clientAuth().keysFile()) + source(config, "client_auth.keys_file"));
        out.println("client_auth.admin_key_file: " + displayPath(config.clientAuth().adminKeyFile()) + source(config, "client_auth.admin_key_file"));
        out.println("client_auth.environment_keys: " + (config.clientAuth().environmentKeys().isEmpty() ? "not set" : "<redacted>"));
        out.println("client_auth.environment_admin_key: " + (config.clientAuth().environmentAdminKey() == null ? "not set" : "<redacted>"));
        out.println("codex.models: " + config.codex().models() + source(config, "codex.models"));
        out.println("codex.auth_mode: " + config.codex().authMode().name().toLowerCase(Locale.ROOT) + source(config, "codex.auth_mode"));
        out.println("codex.native_auth_file: " + config.codex().nativeAuthFile() + source(config, "codex.native_auth_file"));
        out.println("codex.selected_auth_profile: " + (selectedAuth.nativeProfile() ? "native" : "cli"));
        out.println("codex.selected_auth_file: " + displayPath(selectedAuth.path()));
        out.println("codex.effective_base_url: " + (selectedAuth.nativeProfile() ? NativeOAuth.RESOURCE : config.codex().baseUrl()));
        out.println("codex.version: " + config.codex().version() + source(config, "codex.version"));
        out.println("codex.base_url: " + config.codex().baseUrl() + source(config, "codex.base_url"));
        out.println("codex.oauth_file: " + displayPath(config.codex().oauthFile()) + source(config, "codex.oauth_file"));
        out.println("codex.oauth_client_id: " + config.codex().oauthClientId() + source(config, "codex.oauth_client_id"));
        out.println("codex.oauth_token_url: " + config.codex().oauthTokenUrl() + source(config, "codex.oauth_token_url"));
        out.println("codex.store: " + config.codex().store() + source(config, "codex.store"));
        out.println("codex.forward_prompt_cache_headers: " + config.codex().forwardPromptCacheHeaders() + source(config, "codex.forward_prompt_cache_headers"));
        out.println("codex.instructions.mode: " + config.codex().instructionsMode().name().toLowerCase(Locale.ROOT) + source(config, "codex.instructions.mode"));
        out.println("codex.instructions.file: " + displayPath(config.codex().instructionsFile()) + source(config, "codex.instructions.file"));
        out.println("codex.instructions.cache_dir: " + config.codex().instructionsCacheDir() + source(config, "codex.instructions.cache_dir"));
        out.println("anthropic.models: " + config.anthropic().models() + source(config, "anthropic.models"));
        out.println("anthropic.base_url: " + config.anthropic().baseUrl() + source(config, "anthropic.base_url"));
        out.println("anthropic.oauth_file: " + displayPath(config.anthropic().oauthFile()) + source(config, "anthropic.oauth_file"));
        out.println("anthropic.token_url: " + config.anthropic().tokenUrl() + source(config, "anthropic.token_url"));
        out.println("cors.origins: " + config.cors().origins() + source(config, "cors.origins"));
        out.println("cors.allow_any: " + config.cors().allowAny() + source(config, "cors.allow_any"));
        out.println("logging.requests: " + config.logging().requests() + source(config, "logging.requests"));
        out.println("logging.directory: " + config.logging().directory() + source(config, "logging.directory"));
        out.println("startup.check: " + config.startup().check().name().toLowerCase(Locale.ROOT) + source(config, "startup.check"));
        out.flush();
    }

    private static String source(EffectiveConfig config, String key) {
        return "  # source: " + config.sources().getOrDefault(key, "default");
    }

    private static String displayPath(Path path) { return path == null ? "null" : path.toString(); }

}
