package com.aiproxy.bootstrap;

import com.aiproxy.config.ServerConfig;
import com.aiproxy.logging.RequestLogger;
import com.aiproxy.model.ModelCatalog;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.anthropic.AnthropicChatBackend;
import com.aiproxy.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxy.provider.anthropic.AnthropicHttpClient;
import com.aiproxy.provider.anthropic.AnthropicMessagesBackend;
import com.aiproxy.provider.anthropic.AnthropicModelsBackend;
import com.aiproxy.provider.anthropic.AnthropicResponsesBackend;
import com.aiproxy.provider.codex.CodexChatBackend;
import com.aiproxy.provider.codex.CodexHttpClient;
import com.aiproxy.provider.codex.CodexInstructionsProvider;
import com.aiproxy.provider.codex.CodexResponsesBackend;
import com.aiproxy.provider.codex.model.CodexModelCatalog;
import com.aiproxy.provider.codex.model.CodexModelResolver;
import com.aiproxy.provider.copilot.CopilotBackend;
import com.aiproxy.server.ApiKeyStore;
import com.aiproxy.server.InferenceApi;
import com.aiproxy.server.InferenceBackend;
import com.aiproxy.server.InferenceDispatchHandler;
import com.aiproxy.server.MessagesDispatchHandler;
import com.aiproxy.server.ModelsHandler;
import com.aiproxy.server.ProxyEndpoints;
import com.aiproxy.server.ProxyServer;
import com.aiproxy.usage.UsageTracker;
import java.nio.file.Path;
import java.time.Duration;

/** Constructs provider implementations before the HTTP server is configured. */
public final class ProviderAssembly {
    private ProviderAssembly() {}

    public static ProxyServer create(ServerConfig config, CodexHttpClient client, CodexModelResolver modelResolver,
                       UsageTracker usageTracker, ApiKeyStore apiKeyStore) {
        return create(
                config,
                client,
                new CodexModelCatalog(modelResolver),
                usageTracker,
                apiKeyStore
        );
    }

    public static ProxyServer create(ServerConfig config, CodexHttpClient client, ModelCatalog modelCatalog,
                       UsageTracker usageTracker, ApiKeyStore apiKeyStore) {
        return create(config, client, modelCatalog, usageTracker, apiKeyStore,
                null, null, ProviderId.CODEX);
    }

    public static ProxyServer create(ServerConfig config, CodexHttpClient client, ModelCatalog modelCatalog,
                       UsageTracker usageTracker, ApiKeyStore apiKeyStore,
                       AnthropicHttpClient anthropicClient,
                       AnthropicCompatibilityProfile anthropicProfile,
                       ProviderId defaultProvider) {
        return create(config, client, modelCatalog, usageTracker, apiKeyStore, anthropicClient, anthropicProfile,
                defaultProvider, null, null, java.util.Set.of(ProviderId.CODEX, ProviderId.ANTHROPIC), ProviderId.defaultOrder(), false);
    }

    public static ProxyServer create(ServerConfig config, CodexHttpClient client, ModelCatalog modelCatalog,
                                     UsageTracker usageTracker, ApiKeyStore apiKeyStore,
                                     AnthropicHttpClient anthropicClient, AnthropicCompatibilityProfile anthropicProfile,
                                     ProviderId defaultProvider, com.aiproxy.provider.copilot.CopilotHttpClient copilotClient,
                                     com.aiproxy.provider.copilot.model.CopilotModelCatalog copilotCatalog, java.util.Set<ProviderId> enabled,
                                     java.util.List<ProviderId> order, boolean failover) {
        RequestLogger requestLogger = new RequestLogger(config.fullRequestLogging(), Path.of(config.requestLogDir()));
        CodexInstructionsProvider instructionsProvider = "latest-codex".equals(config.codexInstructionsMode())
                ? new CodexInstructionsProvider(
                        CodexInstructionsProvider.Mode.LATEST_CODEX,
                        config.instructions(),
                        Path.of(config.codexInstructionsCacheDir()),
                        Duration.ofMinutes(15),
                        client.getHttpClient()
                )
                : new CodexInstructionsProvider(config.instructions());
        CodexChatBackend codexChat = new CodexChatBackend(
                client, config, usageTracker, requestLogger, instructionsProvider);
        CodexResponsesBackend codexResponses = new CodexResponsesBackend(
                client, config, usageTracker, requestLogger, instructionsProvider);
        String fallbackModel = config.models() != null && !config.models().isEmpty()
                ? config.models().getFirst() : ServerConfig.DEFAULT_MODEL;
        if (client.isNative() && defaultProvider == ProviderId.CODEX) fallbackModel = null;
        InferenceBackend anthropicChat = anthropicClient == null
                ? null
                : new AnthropicChatBackend(
                        anthropicClient, anthropicProfile, usageTracker, requestLogger);
        InferenceBackend anthropicResponses = anthropicClient == null
                ? null
                : new AnthropicResponsesBackend(
                        anthropicClient, anthropicProfile, usageTracker, requestLogger);
        java.util.Map<ProviderId, InferenceBackend> chatBackends = new java.util.EnumMap<>(ProviderId.class);
        java.util.Map<ProviderId, InferenceBackend> responseBackends = new java.util.EnumMap<>(ProviderId.class);
        if (enabled.contains(ProviderId.CODEX)) {
            chatBackends.put(ProviderId.CODEX, codexChat);
            responseBackends.put(ProviderId.CODEX, codexResponses);
        }
        if (anthropicChat != null && enabled.contains(ProviderId.ANTHROPIC)) {
            chatBackends.put(ProviderId.ANTHROPIC, anthropicChat);
            responseBackends.put(ProviderId.ANTHROPIC, anthropicResponses);
        }
        if (copilotClient != null && enabled.contains(ProviderId.COPILOT)) {
            CopilotBackend copilot = new CopilotBackend(copilotClient, copilotCatalog, usageTracker, requestLogger);
            chatBackends.put(ProviderId.COPILOT, copilot);
            responseBackends.put(ProviderId.COPILOT, copilot);
        }
        io.javalin.http.Handler chatHandler = new InferenceDispatchHandler(modelCatalog, defaultProvider, fallbackModel, chatBackends, order, failover, InferenceApi.CHAT_COMPLETIONS);
        io.javalin.http.Handler responsesHandler = new InferenceDispatchHandler(modelCatalog, defaultProvider, fallbackModel, responseBackends, order, failover, InferenceApi.RESPONSES);
        AnthropicMessagesBackend messagesHandler = anthropicClient == null || !enabled.contains(ProviderId.ANTHROPIC)
                ? null
                : new AnthropicMessagesBackend(
                        anthropicClient, anthropicProfile, modelCatalog,
                        usageTracker, requestLogger);
        AnthropicModelsBackend nativeModelsHandler = anthropicClient == null
                ? null
                : new AnthropicModelsBackend(anthropicClient, anthropicProfile, requestLogger);

        var messages = new MessagesDispatchHandler(messagesHandler,
                enabled.contains(ProviderId.COPILOT) && copilotClient != null
                        ? new com.aiproxy.provider.copilot.CopilotMessagesBackend(copilotClient, copilotCatalog, usageTracker) : null,
                requestLogger);
        return new ProxyServer(config, new ProxyEndpoints(new ModelsHandler(modelCatalog, nativeModelsHandler),
                chatHandler, responsesHandler, messages), usageTracker, apiKeyStore, requestLogger);
    }
}
