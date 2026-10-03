package com.aiproxy.cli;

import com.aiproxy.config.ConfigOverrides;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Option;

final class ServeOptions {
    @Option(names = "--config", paramLabel = "<yaml>", description = "Load configuration from this YAML file.")
    String config;
    @Option(names = "--host", paramLabel = "<address>", description = "Host interface to bind to.")
    String host;
    @Option(names = "--port", paramLabel = "<port>", description = "Port to listen on.")
    Integer port;
    @Option(names = "--provider", paramLabel = "<auto|all|both|list>", description = "Upstream providers: codex, anthropic, copilot.")
    String provider;
    @Option(names = "--default-provider", paramLabel = "<provider>", description = "First-choice enabled provider.")
    String defaultProvider;
    @Option(names = "--provider-order", description = "Provider preference order, comma separated.") String providerOrder;
    @Option(names = "--failover", negatable = true, description = "Allow exact-model failover for unqualified requests.") Boolean failover;
    @Option(names = "--copilot-github-host", description = "github.com or tenant.ghe.com.") String copilotGithubHost;
    @Option(names = "--copilot-oauth-file", description = "Proxy-managed Copilot login file.") String copilotOauthFile;
    @Option(names = "--copilot-oauth-client-id", description = "Copilot device authorization client ID.") String copilotOauthClientId;
    @Option(names = "--copilot-token-file", description = "Explicit token file or read-only Copilot CLI JSONC config.") String copilotTokenFile;
    @Option(names = "--copilot-models", description = "Restrict discovered Copilot models (comma separated).") String copilotModels;
    @Option(names = "--startup-check", paramLabel = "<off|credentials|inference>", description = "Startup verification mode.")
    String startupCheck;
    @Option(names = "--client-keys-file", paramLabel = "<path>", description = "File containing proxy client keys.")
    String clientKeysFile;
    @Option(names = "--admin-client-key-file", paramLabel = "<path>", description = "File containing the proxy admin client key.")
    String adminClientKeyFile;
    @Option(names = "--cors-origin", paramLabel = "<origin>", split = ",", description = "Allowed browser origin; repeatable.")
    List<String> corsOrigins;
    @Option(names = "--allow-any-cors", description = "Allow every browser origin (requires client authentication).")
    Boolean allowAnyCors;
    @Option(names = "--log-requests", description = "Store bounded request/response bodies and metadata with sensitive headers redacted.")
    Boolean logRequests;
    @Option(names = "--request-log-dir", paramLabel = "<path>", description = "Directory for protected request logs.")
    String requestLogDir;
    @Option(names = "--codex-models", paramLabel = "<ids>", description = "Comma-separated Codex model override.")
    String codexModels;
    @Option(names = "--codex-version", paramLabel = "<version>", description = "Codex version used for model discovery.")
    String codexVersion;
    @Option(names = "--codex-base-url", paramLabel = "<url>", description = "Codex upstream base URL.")
    String codexBaseUrl;
    @Option(names = "--codex-oauth-file", paramLabel = "<path>", description = "Codex OAuth credential file.")
    String codexOauthFile;
    @Option(names = "--codex-auth-mode", paramLabel = "<auto|native|cli>")
    String codexAuthMode;
    @Option(names = "--codex-native-auth-file", paramLabel = "<path>")
    String codexNativeAuthFile;
    @Option(names = "--codex-oauth-client-id", paramLabel = "<id>", description = "Codex OAuth client ID.")
    String codexOauthClientId;
    @Option(names = "--codex-oauth-token-url", paramLabel = "<url>", description = "Codex OAuth token URL.")
    String codexOauthTokenUrl;
    @Option(names = "--codex-store", description = "Ask Codex upstream to store responses.")
    Boolean codexStore;
    @Option(names = "--codex-forward-prompt-cache-headers", description = "Forward Codex prompt cache headers.")
    Boolean codexForwardPromptCacheHeaders;
    @Option(names = "--codex-instructions-mode", paramLabel = "<none|file|latest>", description = "Codex instructions source.")
    String codexInstructionsMode;
    @Option(names = "--codex-instructions-file", paramLabel = "<path>", description = "Instructions file required by file mode.")
    String codexInstructionsFile;
    @Option(names = "--codex-instructions-cache-dir", paramLabel = "<path>", description = "Cache for latest Codex instructions.")
    String codexInstructionsCacheDir;
    @Option(names = "--anthropic-models", paramLabel = "<ids>", description = "Comma-separated Anthropic model override.")
    String anthropicModels;
    @Option(names = "--anthropic-base-url", paramLabel = "<url>", description = "Anthropic upstream base URL, with or without /v1.")
    String anthropicBaseUrl;
    @Option(names = "--anthropic-oauth-file", paramLabel = "<path>", description = "Anthropic OAuth credential file.")
    String anthropicOauthFile;
    @Option(names = "--anthropic-token-url", paramLabel = "<url>", description = "Anthropic OAuth token URL or default.")
    String anthropicTokenUrl;

    Path configPath() { return config == null ? null : Path.of(config); }

    ConfigOverrides toOverrides() {
        ConfigOverrides value = new ConfigOverrides();
        value.host = host; value.port = port; value.provider = provider; value.defaultProvider = defaultProvider;
        value.providerOrder = providerOrder; value.failover = failover;
        value.copilotGithubHost = copilotGithubHost; value.copilotOauthFile = copilotOauthFile;
        value.copilotOauthClientId = copilotOauthClientId; value.copilotTokenFile = copilotTokenFile;
        value.copilotModels = copilotModels;
        value.startupCheck = startupCheck; value.clientKeysFile = clientKeysFile;
        value.adminClientKeyFile = adminClientKeyFile; value.corsOrigins = corsOrigins;
        value.allowAnyCors = allowAnyCors; value.logRequests = logRequests; value.requestLogDir = requestLogDir;
        value.codexModels = codexModels; value.codexVersion = codexVersion; value.codexBaseUrl = codexBaseUrl;
        value.codexOauthFile = codexOauthFile; value.codexOauthClientId = codexOauthClientId;
        value.codexAuthMode = codexAuthMode; value.codexNativeAuthFile = codexNativeAuthFile;
        value.codexOauthTokenUrl = codexOauthTokenUrl; value.codexStore = codexStore;
        value.codexForwardPromptCacheHeaders = codexForwardPromptCacheHeaders;
        value.codexInstructionsMode = codexInstructionsMode; value.codexInstructionsFile = codexInstructionsFile;
        value.codexInstructionsCacheDir = codexInstructionsCacheDir; value.anthropicModels = anthropicModels;
        value.anthropicBaseUrl = anthropicBaseUrl; value.anthropicOauthFile = anthropicOauthFile;
        value.anthropicTokenUrl = anthropicTokenUrl;
        return value;
    }
}
