package com.aiproxy.bootstrap;

import com.aiproxy.cli.StartupRenderer;
import com.aiproxy.config.ConfigException;
import com.aiproxy.config.EffectiveConfig;
import com.aiproxy.config.ServerConfig;
import com.aiproxy.logging.RequestLogger;
import com.aiproxy.model.CompositeModelCatalog;
import com.aiproxy.model.ModelCatalog;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.model.ProviderModelCatalog;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxy.provider.anthropic.AnthropicHttpClient;
import com.aiproxy.provider.anthropic.auth.AnthropicAuthManager;
import com.aiproxy.provider.anthropic.auth.AnthropicCredentialStore;
import com.aiproxy.provider.anthropic.auth.AnthropicOAuthClient;
import com.aiproxy.provider.anthropic.model.AnthropicModelCatalog;
import com.aiproxy.provider.codex.CodexHttpClient;
import com.aiproxy.provider.codex.auth.CodexAuthFileResolver;
import com.aiproxy.provider.codex.auth.CodexAuthLoader;
import com.aiproxy.provider.codex.auth.CodexAuthManager;
import com.aiproxy.provider.codex.auth.nativeoauth.CodexAuthSelection;
import com.aiproxy.provider.codex.auth.nativeoauth.NativeCredentialStore;
import com.aiproxy.provider.codex.auth.nativeoauth.NativeOAuth;
import com.aiproxy.provider.codex.auth.nativeoauth.NativeSession;
import com.aiproxy.provider.codex.model.CodexModelCatalog;
import com.aiproxy.provider.codex.model.CodexModelResolver;
import com.aiproxy.provider.copilot.auth.CopilotCredentials;
import com.aiproxy.provider.copilot.model.CopilotModelCatalog;
import com.aiproxy.server.ApiKeyStore;
import com.aiproxy.server.ProxyServer;
import com.aiproxy.sse.ServerSentEvent;
import com.aiproxy.sse.SseParser;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.ApiKeyUtils;
import com.aiproxy.util.Json;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/** Owns provider startup, diagnostics and shutdown; independent of CLI parsing. */
public class ProxyRuntime {
    private final Supplier<Map<String, String>> environment;

    public ProxyRuntime() { this(System::getenv); }

    public ProxyRuntime(Supplier<Map<String, String>> environment) { this.environment = environment; }

    public Integer run(EffectiveConfig effective, boolean doctorMode,
                       java.io.PrintWriter out, java.io.PrintWriter err) throws Exception {
        CodexAuthSelection codexSelection;
        try {
            codexSelection = CodexAuthSelection.select(effective.codex());
        } catch (ConfigException error) {
            err.println("Configuration error: " + error.getMessage());
            return 2;
        }
        Map<String, String> configuredKeys = new HashMap<>(effective.clientAuth().environmentKeys());
        if (effective.clientAuth().keysFile() != null) {
            Files.readAllLines(effective.clientAuth().keysFile()).stream()
                    .map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> ApiKeyUtils.parseKeyEntry(line, configuredKeys));
            if (configuredKeys.isEmpty()) throw new ConfigException("client keys file contains no keys");
        }
        String configuredAdmin = effective.clientAuth().environmentAdminKey();
        if (effective.clientAuth().adminKeyFile() != null) {
            configuredAdmin = Files.readString(effective.clientAuth().adminKeyFile()).strip();
            if (configuredAdmin.isBlank()) throw new ConfigException("admin client key file is empty");
        }
        ServerConfig config = effective.legacyServerConfig(configuredKeys, configuredAdmin);
        if (config.fullRequestLogging()) {
            System.err.println("WARNING: full request logging is enabled. Request/response bodies may contain prompts, "
                    + "tool outputs, file paths, and other sensitive data. Authorization and API key headers are "
                    + "redacted, but logs should still be protected.");
        }

        String codexAuthPath = codexSelection.path() == null ? null : codexSelection.path().toString();
        Path anthropicCredentialPath = effective.anthropic().oauthFile();
        boolean anthropicCredentialAvailable =
                hasText(environment.get().get("CLAUDE_CODE_OAUTH_TOKEN"))
                        || Files.isRegularFile(anthropicCredentialPath);
        Set<ProviderId> enabledProviders;
        ProviderId effectiveDefaultProvider;
        try {
            String selectedProviders = effective.routing().selection();
            enabledProviders = ProviderStartupResolver.resolve(
                    selectedProviders, codexSelection.available(), anthropicCredentialAvailable,
                    new CopilotCredentials(effective.copilot()).available());
            String requestedDefault = "default".equals(effective.sources().get("routing.default_provider"))
                    ? null : effective.routing().defaultProvider().wireName();
            effectiveDefaultProvider = ProviderStartupResolver.resolveDefault(
                    requestedDefault, enabledProviders, effective.routing().providerOrder());
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            return 1;
        }

        HttpClient authHttpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpClient nativeOAuthHttp = codexSelection.nativeProfile()
                ? HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build() : null;
        AnthropicCredentialStore anthropicStore = null;
        com.aiproxy.provider.copilot.CopilotHttpClient copilotClient = null;
        CodexHttpClient httpClient = null;
        ApiKeyStore apiKeyStore = null;
        ProxyServer server = null;
        try {
            NativeSession nativeSession = codexSelection.nativeProfile()
                    ? new NativeSession(new NativeCredentialStore(codexSelection.path()),
                        new NativeOAuth(nativeOAuthHttp, Clock.systemUTC()), Clock.systemUTC()) : null;
            CodexAuthManager authManager = new CodexAuthManager(config, authHttpClient, nativeSession,
                    codexSelection.nativeProfile() ? null : codexAuthPath);
            CodexAuthLoader.AuthResult authResult = null;
            String codexCredentialError = null;
            if (enabledProviders.contains(ProviderId.CODEX)) {
                try {
                    authResult = authManager.ensureFresh();
                } catch (Exception error) {
                    codexCredentialError = error.getMessage();
                }
            }

            httpClient = new CodexHttpClient(config, authManager);
            CodexModelResolver modelResolver = new CodexModelResolver(httpClient, config.models(), config.codexVersion());
            List<ProviderModelCatalog> catalogs = new ArrayList<>();
            if (enabledProviders.contains(ProviderId.CODEX)) {
                catalogs.add(new CodexModelCatalog(modelResolver));
            }

            AnthropicModelCatalog anthropicResolver = null;
            AnthropicCompatibilityProfile activeAnthropicProfile = null;
            AnthropicHttpClient activeAnthropicHttpClient = null;
            AnthropicAuthManager activeAnthropicAuth = null;
            if (enabledProviders.contains(ProviderId.ANTHROPIC)) {
                AnthropicCompatibilityProfile profile = anthropicProfile(effective);
                activeAnthropicProfile = profile;
                anthropicStore = AnthropicCredentialStore.open(anthropicCredentialPath);
                AnthropicAuthManager anthropicAuth = new AnthropicAuthManager(
                        anthropicStore,
                        new AnthropicOAuthClient(profile, authHttpClient),
                        Clock.systemUTC(),
                        environment.get()
                );
                activeAnthropicAuth = anthropicAuth;
                AnthropicHttpClient anthropicHttpClient = new AnthropicHttpClient(
                        profile,
                        authHttpClient,
                        anthropicAuth,
                        new RequestLogger(
                                config.fullRequestLogging(),
                                Path.of(config.requestLogDir())
                        )
                );
                activeAnthropicHttpClient = anthropicHttpClient;
                anthropicResolver = new AnthropicModelCatalog(
                        anthropicHttpClient,
                        profile,
                        effective.anthropic().models(),
                        Clock.systemUTC()
                );
                catalogs.add(anthropicResolver);
            }
            com.aiproxy.provider.copilot.model.CopilotModelCatalog copilotCatalog = null;
            if (enabledProviders.contains(ProviderId.COPILOT)) {
                copilotClient = new com.aiproxy.provider.copilot.CopilotHttpClient(effective.copilot());
                copilotCatalog = new com.aiproxy.provider.copilot.model.CopilotModelCatalog(copilotClient, effective.copilot().models(), Clock.systemUTC());
                catalogs.add(copilotCatalog);
            }
            ModelCatalog modelCatalog = catalogs.size() == 1
                    ? catalogs.getFirst()
                    : new CompositeModelCatalog(catalogs);

            // Discover models upfront
            List<String> availableModels = resolveAvailableModels(modelCatalog);

            Map<String, String> inlineKeys = new HashMap<>(effective.clientAuth().environmentKeys());
            if (config.adminKey() != null) inlineKeys.remove(config.adminKey());
            String explicitAdminKey = config.adminKey();
            String keysFile = effective.clientAuth().keysFile() == null ? null : effective.clientAuth().keysFile().toString();
            apiKeyStore = new ApiKeyStore(inlineKeys, keysFile, explicitAdminKey);
            if (keysFile != null) {
                apiKeyStore.reload();
            }
            if (!doctorMode) apiKeyStore.startWatching();

            // Start server
            UsageTracker usageTracker = new UsageTracker();
            if (!doctorMode || effective.startup().check() == EffectiveConfig.StartupCheck.INFERENCE) {
                server = ProviderAssembly.create(
                    config, httpClient, modelCatalog, usageTracker, apiKeyStore,
                    activeAnthropicHttpClient, activeAnthropicProfile, effectiveDefaultProvider,
                    copilotClient, copilotCatalog, enabledProviders, effective.routing().providerOrder(), effective.routing().failover());
                if (doctorMode) server.getApp().start("127.0.0.1", 0);
                else server.start();
            }
            ServerConfig probeConfig = doctorMode && server != null ? probeConfig(config, server.getApp().port()) : config;

            Map<ProviderId, StartupRenderer.Check> checks = new HashMap<>();
            if (effective.startup().check() == EffectiveConfig.StartupCheck.OFF) {
                enabledProviders.forEach(provider -> checks.put(provider, StartupRenderer.Check.skipped()));
            } else if (effective.startup().check() == EffectiveConfig.StartupCheck.CREDENTIALS) {
                if (copilotClient != null) {
                    try {
                        copilotClient.validateCredentials();
                        checks.put(ProviderId.COPILOT, StartupRenderer.Check.ok("credentials"));
                    } catch (Exception error) {
                        checks.put(ProviderId.COPILOT, StartupRenderer.Check.failed("credentials", error.getMessage()));
                    }
                }
                if (enabledProviders.contains(ProviderId.CODEX)) {
                    checks.put(ProviderId.CODEX, authResult != null
                            ? StartupRenderer.Check.ok("credentials")
                            : StartupRenderer.Check.failed("credentials", codexCredentialError));
                }
                if (enabledProviders.contains(ProviderId.ANTHROPIC)) {
                    try {
                        activeAnthropicAuth.accessToken();
                        checks.put(ProviderId.ANTHROPIC, StartupRenderer.Check.ok("credentials"));
                    } catch (Exception error) {
                        checks.put(ProviderId.ANTHROPIC, StartupRenderer.Check.failed("credentials", error.getMessage()));
                    }
                }
            } else {
                String startupKey = apiKeyStore.probeKey();
                try (HttpClient startupProbeClient = HttpClient.newHttpClient()) {
                    if (copilotClient != null) {
                        List<String> models = resolveAvailableModels(modelCatalog, ProviderId.COPILOT);
                        checks.put(ProviderId.COPILOT, models.isEmpty()
                                ? StartupRenderer.Check.failed("inference", "No Copilot models discovered")
                                : check(verifyChatCompletionThroughProxy(probeConfig, models.stream().map(model -> "copilot/" + model).toList(), startupKey, startupProbeClient)));
                    }
                    if (enabledProviders.contains(ProviderId.CODEX)) {
                        List<String> codexModels = resolveAvailableModels(modelCatalog, ProviderId.CODEX);
                        StartupProbeResult probe = verifyChatCompletionThroughProxy(probeConfig,
                                codexModels.stream().map(model -> "codex/" + model).toList(), startupKey, startupProbeClient);
                        checks.put(ProviderId.CODEX, check(probe));
                    }
                    if (enabledProviders.contains(ProviderId.ANTHROPIC)) {
                        StartupProbeResult probe = verifyAnthropicThroughProxy(probeConfig,
                                resolveAvailableModels(modelCatalog, ProviderId.ANTHROPIC), startupKey, startupProbeClient);
                        checks.put(ProviderId.ANTHROPIC, check(probe));
                    }
                }
            }

            Map<ProviderId, StartupRenderer.ProviderStatus> statuses = new java.util.LinkedHashMap<>();
            if (copilotClient != null) {
                CopilotModelCatalog catalog = copilotCatalog;
                String kind = effective.copilot().tokenFile() == null && effective.copilot().environmentToken() != null
                        ? "environment: " : "file: ";
                String source = kind + new CopilotCredentials(effective.copilot()).source() + " (GitHub bearer)";
                statuses.put(ProviderId.COPILOT, providerStatus(source, catalog,
                        () -> copilotModelSource(catalog), catalog::lastFailure, checks.get(ProviderId.COPILOT)));
            }
            if (enabledProviders.contains(ProviderId.CODEX)) {
                String source = "file: " + (authResult != null && authResult.sourcePath() != null
                        ? authResult.sourcePath() : codexAuthPath)
                        + (codexSelection.nativeProfile() ? " (native OAuth)" : " (CLI OAuth)");
                statuses.put(ProviderId.CODEX, providerStatus(source, new CodexModelCatalog(modelResolver),
                        () -> codexModelSource(modelResolver), () -> null, checks.get(ProviderId.CODEX)));
            }
            if (enabledProviders.contains(ProviderId.ANTHROPIC)) {
                AnthropicModelCatalog resolver = anthropicResolver;
                String authSource = hasText(environment.get().get("CLAUDE_CODE_OAUTH_TOKEN"))
                        ? "environment: CLAUDE_CODE_OAUTH_TOKEN" : "file: " + anthropicCredentialPath;
                statuses.put(ProviderId.ANTHROPIC, providerStatus(authSource + " (OAuth)", resolver,
                        () -> anthropicModelSource(resolver),
                        () -> resolver.lastFailure().map(AnthropicModelCatalog.Failure::message).orElse(null),
                        checks.get(ProviderId.ANTHROPIC)));
            }
            EffectiveConfig displayConfig = effectiveDefaultProvider == effective.routing().defaultProvider()
                    ? effective
                    : new EffectiveConfig(effective.server(),
                    effective.routing().withDefault(effectiveDefaultProvider),
                    effective.clientAuth(), effective.codex(), effective.anthropic(), effective.copilot(), effective.cors(),
                    effective.logging(), effective.startup(), effective.sources());
            out.print(StartupRenderer.render(displayConfig, statuses, doctorMode));
            out.flush();
            if (doctorMode) {
                boolean failed = checks.values().stream().anyMatch(check -> check.state() == StartupRenderer.Check.State.FAILED);
                failed |= statuses.values().stream().anyMatch(StartupRenderer.ProviderStatus::hasModelWarning);
                return failed ? 1 : 0;
            }
            setupShutdownHook(server, authHttpClient, apiKeyStore, anthropicStore);
            if (nativeOAuthHttp != null) Runtime.getRuntime().addShutdownHook(new Thread(nativeOAuthHttp::close));
            if (copilotClient != null) {
                var shutdownClient = copilotClient;
                Runtime.getRuntime().addShutdownHook(new Thread(shutdownClient::close));
            }

            // Keep main thread alive
            Thread.currentThread().join();
            return 0;
        } finally {
            if (doctorMode) {
                if (server != null) server.stop();
                if (apiKeyStore != null) apiKeyStore.stopWatching();
                try {
                    if (anthropicStore != null) anthropicStore.close();
                } finally {
                    authHttpClient.close();
                    if (nativeOAuthHttp != null) nativeOAuthHttp.close();
                    if (httpClient != null) httpClient.getHttpClient().close();
                    if (copilotClient != null) copilotClient.close();
                }
            }
        }
    }

    public boolean checkAuthFileExists(ServerConfig config) {
        String existingAuthFile = findExistingAuthFile(config.oauthFilePath());
        if (existingAuthFile == null) {
            List<String> candidates = CodexAuthFileResolver.resolveCandidates(config.oauthFilePath());
            if (config.oauthFilePath() != null && !config.oauthFilePath().isEmpty()) {
                System.err.println("No auth file was found at " + config.oauthFilePath() + ".");
            } else {
                System.err.println("No auth file was found in the default search paths: "
                        + String.join(", ", candidates) + ".");
            }
            System.err.println("Run `codex login` and try again.");
            return false;
        }
        return true;
    }

    public List<String> resolveAvailableModels(CodexModelResolver modelResolver) {
        try {
            return modelResolver.resolveModels();
        } catch (Exception e) {
            System.err.println("Warning: Could not discover models: " + e);
            return List.of();
        }
    }

    public List<String> resolveAvailableModels(ModelCatalog modelCatalog) {
        try {
            return modelCatalog.resolveModels().stream()
                    .map(ProviderModel::id)
                    .toList();
        } catch (Exception error) {
            System.err.println("Warning: Could not discover models: " + error);
            return List.of();
        }
    }

    public List<String> resolveAvailableModels(ModelCatalog modelCatalog, ProviderId provider) {
        try {
            return modelCatalog.resolveModels().stream()
                    .filter(model -> model.provider() == provider)
                    .map(ProviderModel::id)
                    .toList();
        } catch (Exception error) {
            System.err.println("Warning: Could not discover models for "
                    + provider.wireName() + ": " + error);
            return List.of();
        }
    }

    private AnthropicCompatibilityProfile anthropicProfile(EffectiveConfig effective) {
        AnthropicCompatibilityProfile source =
                AnthropicCompatibilityProfile.claudeCodeOAuth();
        String base = effective.anthropic().baseUrl();
        URI tokenUri = !"default".equalsIgnoreCase(effective.anthropic().tokenUrl())
                ? URI.create(effective.anthropic().tokenUrl())
                : source.tokenUri();
        return new AnthropicCompatibilityProfile(
                source.name(),
                source.clientId(),
                source.authorizationUri(),
                tokenUri,
                source.redirectUri(),
                URI.create(base + "/v1/messages?beta=true"),
                URI.create(base + "/v1/models?limit=100"),
                source.scopes(),
                source.anthropicVersion(),
                source.oauthBeta(),
                source.claudeCodeBeta(),
                source.oauthSystemPreamble()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record StartupProbeResult(boolean success, int statusCode, String message, String responseText, String model) {}

    private static final int STARTUP_PROBE_MAX_MODELS = 6;

    public StartupProbeResult verifyChatCompletionThroughProxy(
            ServerConfig config,
            List<String> availableModels,
            String apiKey,
            HttpClient httpClient
    ) {
        // Probe the preferred model first, then fall back through other discovered models until one
        // succeeds. A catalog can list models the caller's credential cannot use for chat (e.g.
        // Copilot models restricted to a different integrator), which would otherwise fail the whole
        // provider on an unlucky first pick. Capped so a fully broken provider does not fan out to
        // every model.
        List<String> candidates = startupProbeModelCandidates(config, availableModels);
        StartupProbeResult firstFailure = null;
        for (String model : candidates) {
            StartupProbeResult result = probeChatModel(config, model, apiKey, httpClient);
            if (result.success()) {
                return result;
            }
            if (firstFailure == null) {
                firstFailure = result;
            }
        }
        return firstFailure != null ? firstFailure
                : new StartupProbeResult(false, 0, "No models available to probe", null,
                        selectStartupProbeModel(config, availableModels));
    }

    private List<String> startupProbeModelCandidates(ServerConfig config, List<String> availableModels) {
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        ordered.add(selectStartupProbeModel(config, availableModels));
        if (availableModels != null) {
            ordered.addAll(availableModels);
        }
        return ordered.stream().limit(STARTUP_PROBE_MAX_MODELS).toList();
    }

    private StartupProbeResult probeChatModel(
            ServerConfig config,
            String model,
            String apiKey,
            HttpClient httpClient
    ) {
        String body = """
                {"model":"%s","messages":[{"role":"user","content":"Hello!"}],"stream":true}
                """.formatted(model);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(startupProbeUrl(config)))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            int status = response.statusCode();
            String responseText = status >= 200 && status < 300
                    ? extractStartupProbeResponseText(response.body())
                    : formatStartupProbeRawBody(response.body());
            if (status >= 200 && status < 300) {
                boolean hasModelResponse = hasActualStartupProbeResponse(responseText);
                return new StartupProbeResult(
                        hasModelResponse,
                        status,
                        hasModelResponse ? "HTTP " + status : "HTTP " + status + ", no model response text",
                        responseText,
                        model
                );
            }
            return new StartupProbeResult(false, status, "HTTP " + status, responseText, model);
        } catch (Exception e) {
            return new StartupProbeResult(false, 0, e.getClass().getSimpleName() + ": " + e.getMessage(), null, model);
        }
    }

    private static String extractStartupProbeResponseText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "<empty response body>";
        }
        if (looksLikeSse(responseBody)) {
            return extractStreamingStartupProbeResponseText(responseBody);
        }
        try {
            JsonNode root = Json.MAPPER.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return "<missing choices[0].message.content>";
            }
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice != null ? firstChoice.get("message") : null;
            JsonNode content = message != null ? message.get("content") : null;
            if (content == null) {
                return "<missing choices[0].message.content>";
            }
            if (content.isNull()) {
                return "<null choices[0].message.content>";
            }
            if (content.isString()) {
                return formatStartupProbeText(content.asString());
            }
            return formatStartupProbeText(Json.MAPPER.writeValueAsString(content));
        } catch (Exception e) {
            return "<unparseable response body: " + formatStartupProbeText(responseBody) + ">";
        }
    }

    private static String extractStreamingStartupProbeResponseText(String responseBody) {
        StringBuilder text = new StringBuilder();
        boolean sawNullContent = false;
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
            for (ServerSentEvent event : SseParser.parse(input)) {
                String data = event.data();
                if (data == null || data.isBlank()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }

                JsonNode root = Json.MAPPER.readTree(data);
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray()) {
                    continue;
                }
                for (JsonNode choice : choices) {
                    JsonNode delta = choice.get("delta");
                    JsonNode content = delta != null ? delta.get("content") : null;
                    if (content == null) {
                        continue;
                    }
                    if (content.isNull()) {
                        sawNullContent = true;
                    } else if (content.isString()) {
                        text.append(content.asString());
                    } else {
                        text.append(Json.MAPPER.writeValueAsString(content));
                    }
                }
            }
        } catch (Exception e) {
            return "<unparseable streaming response body: " + formatStartupProbeText(responseBody) + ">";
        }

        if (!text.isEmpty()) {
            return formatStartupProbeText(text.toString());
        }
        return sawNullContent
                ? "<null streaming choices[].delta.content>"
                : "<missing streaming choices[].delta.content>";
    }

    private static boolean looksLikeSse(String responseBody) {
        String trimmed = responseBody.stripLeading();
        return trimmed.startsWith("data:") || trimmed.startsWith("event:");
    }

    private static boolean hasActualStartupProbeResponse(String responseText) {
        return responseText != null && !responseText.isBlank() && !responseText.startsWith("<");
    }

    private static String formatStartupProbeRawBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "<empty response body>";
        }
        return formatStartupProbeText(responseBody);
    }

    private static String formatStartupProbeText(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String selectStartupProbeModel(ServerConfig config, List<String> availableModels) {
        if (availableModels != null && !availableModels.isEmpty()) {
            return selectProviderModel(availableModels, ServerConfig.DEFAULT_MODEL);
        }
        if (config.models() != null && !config.models().isEmpty()) {
            return selectProviderModel(config.models(), ServerConfig.DEFAULT_MODEL);
        }
        return ServerConfig.DEFAULT_MODEL;
    }

    private static String startupProbeUrl(ServerConfig config) {
        String host = clientHostForBindHost(config.host());
        return "http://" + hostForUri(host) + ":" + config.port() + "/v1/chat/completions";
    }

    private static String clientHostForBindHost(String host) {
        if (host == null || host.isBlank()) {
            return ServerConfig.DEFAULT_HOST;
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if ("0.0.0.0".equals(normalized) || "::".equals(normalized) || "0:0:0:0:0:0:0:0".equals(normalized)) {
            return ServerConfig.DEFAULT_HOST;
        }
        return host.strip();
    }

    private static String hostForUri(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static String firstConfiguredApiKey(ServerConfig config) {
        if (config.adminKey() != null) {
            return config.adminKey();
        }
        return config.apiKeys().keySet().stream().findFirst().orElse(null);
    }

    public void setupShutdownHook(ProxyServer server, HttpClient authHttpClient, ApiKeyStore apiKeyStore) {
        setupShutdownHook(server, authHttpClient, apiKeyStore, null);
    }

    public void setupShutdownHook(
            ProxyServer server,
            HttpClient authHttpClient,
            ApiKeyStore apiKeyStore,
            AutoCloseable anthropicStore
    ) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.stop();
            authHttpClient.close();
            apiKeyStore.stopWatching();
            if (anthropicStore != null) {
                try {
                    anthropicStore.close();
                } catch (Exception error) {
                    System.err.println(
                            "Warning: failed to close Anthropic credential store: "
                                    + error.getMessage());
                }
            }
        }, "shutdown-hook"));
    }

    public static String findExistingAuthFile(String authFilePath) {
        for (String candidate : CodexAuthFileResolver.resolveCandidates(authFilePath)) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    public StartupProbeResult verifyAnthropicThroughProxy(
            ServerConfig config,
            List<String> availableModels,
            String apiKey,
            HttpClient httpClient
    ) {
        String model = selectProviderModel(availableModels, "claude-sonnet-4-5");
        String body;
        try {
            body = Json.MAPPER.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", 16,
                    "messages", List.of(Map.of("role", "user", "content", "Reply OK"))));
        } catch (Exception error) {
            return new StartupProbeResult(false, 0, error.getMessage(), null, model);
        }
        String host = clientHostForBindHost(config.host());
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + hostForUri(host) + ":" + config.port() + "/v1/messages"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank()) request.header("x-api-key", apiKey);
        try {
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String responseText = formatStartupProbeRawBody(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = Json.MAPPER.readTree(response.body());
                // A well-formed 200 message proves the inference round-trip works even when the
                // content is empty (e.g. a low max_tokens response that stops before emitting text).
                boolean wellFormed = "message".equals(root.path("type").asString())
                        || "assistant".equals(root.path("role").asString());
                String text = root.path("content").isArray() && !root.path("content").isEmpty()
                        ? root.path("content").get(0).path("text").asString("") : "";
                String detail = !text.isBlank() ? formatStartupProbeText(text)
                        : wellFormed ? "stop_reason=" + root.path("stop_reason").asString("unknown") : responseText;
                return new StartupProbeResult(wellFormed, response.statusCode(),
                        wellFormed ? "HTTP " + response.statusCode() : "HTTP " + response.statusCode() + ", unexpected response shape",
                        detail, model);
            }
            return new StartupProbeResult(false, response.statusCode(), "HTTP " + response.statusCode(), responseText, model);
        } catch (Exception error) {
            return new StartupProbeResult(false, 0, error.getClass().getSimpleName() + ": " + error.getMessage(), null, model);
        }
    }

    private static StartupRenderer.Check check(StartupProbeResult probe) {
        return probe.success()
                ? StartupRenderer.Check.ok(probe.model())
                : StartupRenderer.Check.failed(probe.model(), probe.message() + (probe.responseText() == null ? "" : ": " + probe.responseText()));
    }

    private static String selectProviderModel(List<String> models, String fallback) {
        if (models == null || models.isEmpty()) return fallback;
        return models.stream().filter(model -> {
            if (model == null) return false;
            String name = model.toLowerCase(Locale.ROOT);
            return name.contains("luna") || name.contains("haiku");
        }).findFirst().orElse(models.getLast());
    }

    public static StartupRenderer.ProviderStatus providerStatus(String credentialSource, ProviderModelCatalog catalog,
            java.util.function.Supplier<String> modelSource, java.util.function.Supplier<String> diagnostic,
            StartupRenderer.Check check) {
        try {
            List<String> models = catalog.resolveModels().stream().map(ProviderModel::id).toList();
            return new StartupRenderer.ProviderStatus(credentialSource, models,
                    models.isEmpty() ? "unavailable" : modelSource.get(), diagnostic.get(), check);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new StartupRenderer.ProviderStatus(credentialSource, List.of(), "unavailable", error.getMessage(), check);
        }
    }

    public static String copilotModelSource(CopilotModelCatalog catalog) {
        return switch (catalog.source()) {
            case DISCOVERED -> "discovered";
            case CACHE -> "cache";
            case LAST_GOOD -> "stale cache";
            default -> "unavailable";
        };
    }

    public static String anthropicModelSource(AnthropicModelCatalog resolver) {
        return switch (resolver.source()) {
            case CONFIGURED_FALLBACK -> "configured";
            case DISCOVERED -> "discovered";
            case CACHE -> "cache";
            case LAST_GOOD -> "stale cache";
            case SEED_FALLBACK -> "fallback";
            default -> "unavailable";
        };
    }

    public static String codexModelSource(CodexModelResolver resolver) {
        return switch (resolver.source()) {
            case CONFIGURED -> "configured";
            case CACHE -> "cache";
            case DISCOVERED -> "discovered";
            default -> "unavailable";
        };
    }

    private static ServerConfig probeConfig(ServerConfig config, int port) {
        return new ServerConfig("127.0.0.1", port, config.models(), config.codexVersion(), config.baseUrl(),
                config.oauthClientId(), config.oauthTokenUrl(), config.oauthFilePath(), config.instructions(),
                config.store(), config.apiKeys(), config.adminKey(), config.allowAnyCors(), config.allowedCorsOrigins(),
                config.fullRequestLogging(), config.requestLogDir(), config.forwardPromptCacheHeaders(),
                config.codexInstructionsMode(), config.codexInstructionsCacheDir());
    }

}
