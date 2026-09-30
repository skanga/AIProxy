package com.aiproxyoauth;

import com.aiproxyoauth.config.ConfigOverrides;
import com.aiproxyoauth.config.EffectiveConfigLoader;
import com.aiproxyoauth.model.*;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.provider.copilot.CopilotClient;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.util.Json;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StartupProviderStatusTest {
    @Test
    void copilotReportsDiscoveryFreshCacheStaleCacheAndUnavailableWithoutInventingModels() throws Exception {
        CopilotClient client = mock();
        Clock clock = mock();
        Instant start = Instant.parse("2026-09-30T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        when(client.identity()).thenReturn("account");
        when(client.models()).thenReturn(Json.MAPPER.readTree("{\"data\":[{\"id\":\"test\"}]}"));
        var catalog = new CopilotModelCatalog(client, List.of(), clock);
        assertEquals("discovered", copilotStatus(catalog).modelSource());
        assertEquals("cache", copilotStatus(catalog).modelSource());
        verify(client, times(1)).models();

        when(client.models()).thenThrow(new IOException("Copilot models returned HTTP 503"));
        when(clock.instant()).thenReturn(start.plusSeconds(301));
        var stale = copilotStatus(catalog);
        assertEquals("stale cache", stale.modelSource());
        assertEquals(List.of("test"), stale.models());
        assertTrue(stale.modelDiagnostic().contains("503"));

        when(clock.instant()).thenReturn(start.plusSeconds(3601));
        var unavailable = copilotStatus(catalog);
        assertEquals("unavailable", unavailable.modelSource());
        assertTrue(unavailable.models().isEmpty());

        doReturn(Json.MAPPER.readTree("{\"data\":[{\"id\":\"recovered\"}]}" )).when(client).models();
        var recovered = copilotStatus(catalog);
        assertEquals("discovered", recovered.modelSource());
        assertNull(recovered.modelDiagnostic());
        assertFalse(recovered.hasModelWarning());
    }

    @Test
    void copilotDoesNotReportAnOldAccountsCatalogOrAnEmptyAllowlistAsAvailable() throws Exception {
        CopilotClient client = mock();
        when(client.identity()).thenReturn("first");
        when(client.models()).thenReturn(Json.MAPPER.readTree("{\"data\":[{\"id\":\"test\"}]}"));
        var catalog = new CopilotModelCatalog(client, List.of(), Clock.systemUTC());
        assertEquals("discovered", copilotStatus(catalog).modelSource());
        when(client.identity()).thenReturn("second");
        when(client.models()).thenThrow(new IOException("offline"));
        assertEquals("unavailable", copilotStatus(catalog).modelSource());
        when(client.identity()).thenThrow(new IOException("credentials unavailable"));
        assertTrue(copilotStatus(catalog).models().isEmpty());
        assertEquals(CopilotModelCatalog.Source.UNAVAILABLE, catalog.source());

        doReturn(Json.MAPPER.readTree("{\"data\":[{\"id\":\"test\"}]}" )).when(client).models();
        doReturn("second").when(client).identity();
        var filtered = new CopilotModelCatalog(client, List.of("not-listed"), Clock.systemUTC());
        assertEquals("unavailable", copilotStatus(filtered).modelSource());
    }

    @Test
    void codexDistinguishesDiscoveryCacheConfigurationAndFailure() throws Exception {
        CodexHttpClient client = mock();
        HttpResponse<String> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"models\":[{\"slug\":\"test\"}]}");
        when(client.requestString(anyString(), eq("GET"), isNull(), isNull())).thenReturn(response);
        var resolver = new ModelResolver(client, List.of(), "test-version");
        assertEquals("discovered", codexStatus(resolver).modelSource());
        assertEquals("cache", codexStatus(resolver).modelSource());
        assertEquals("configured", codexStatus(new ModelResolver(client, List.of("override"), "test-version")).modelSource());
        when(response.statusCode()).thenReturn(403);
        var failed = codexStatus(new ModelResolver(client, List.of(), "test-version"));
        assertEquals("unavailable", failed.modelSource());
        assertTrue(failed.models().isEmpty());
        assertNotNull(failed.modelDiagnostic());
    }

    @Test
    void anthropicDistinguishesFreshAndStaleCachesAndExplainsBuiltInFallback() throws Exception {
        AnthropicHttpClient client = mock();
        Clock clock = mock();
        Instant start = Instant.parse("2026-09-30T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        when(client.request(any(), eq("GET"), isNull(), anyMap(), any()))
                .thenAnswer(call -> response(200, "{\"data\":[{\"id\":\"claude-test\"}]}"));
        var profile = AnthropicCompatibilityProfile.claudeCodeOAuth();
        var resolver = new AnthropicModelResolver(client, profile, List.of(), clock);
        assertEquals("discovered", anthropicStatus(resolver).modelSource());
        assertEquals("cache", anthropicStatus(resolver).modelSource());
        when(clock.instant()).thenReturn(start.plusSeconds(301));
        when(client.request(any(), eq("GET"), isNull(), anyMap(), any()))
                .thenAnswer(call -> response(403, "{}"));
        var stale = anthropicStatus(resolver);
        assertEquals("stale cache", stale.modelSource());
        assertTrue(stale.modelDiagnostic().contains("403"));
        var fallback = anthropicStatus(new AnthropicModelResolver(client, profile, List.of(), clock));
        assertEquals("fallback", fallback.modelSource());
        assertEquals(3, fallback.models().size());
        String rendered = StartupRenderer.render(EffectiveConfigLoader.load(null, Map.of(), new ConfigOverrides()),
                Map.of(ProviderId.ANTHROPIC, fallback));
        assertTrue(rendered.contains("fallback (built-in)"), rendered);
        assertTrue(rendered.contains("Anthropic model discovery returned HTTP 403"), rendered);
        assertTrue(rendered.endsWith("Ready with warnings.\n"), rendered);
        assertEquals("configured", anthropicStatus(new AnthropicModelResolver(client, profile, List.of("override"), clock)).modelSource());
    }

    @Test
    void discoveryDiagnosticsAreRedactedAndCannotInjectBannerLines() throws Exception {
        ProviderModelCatalog catalog = mock();
        when(catalog.resolveModels()).thenThrow(new IOException("token=secret\nBearer private user@example.com"));
        var status = AIProxyOauth.providerStatus("file: auth.json", catalog, () -> "discovered", () -> null,
                StartupRenderer.Check.skipped());
        String rendered = StartupRenderer.render(EffectiveConfigLoader.load(null, Map.of(), new ConfigOverrides()),
                Map.of(ProviderId.CODEX, status));
        assertFalse(rendered.contains("secret"), rendered);
        assertFalse(rendered.contains("private"), rendered);
        assertFalse(rendered.contains("user@example.com"), rendered);
        assertTrue(rendered.contains("token=<redacted> Bearer <redacted> <redacted-email>"), rendered);
    }

    private static StartupRenderer.ProviderStatus copilotStatus(CopilotModelCatalog catalog) {
        return AIProxyOauth.providerStatus("file: copilot.json (GitHub bearer)", catalog,
                () -> AIProxyOauth.copilotModelSource(catalog), catalog::lastFailure, StartupRenderer.Check.skipped());
    }

    private static StartupRenderer.ProviderStatus codexStatus(ModelResolver resolver) {
        return AIProxyOauth.providerStatus("file: codex.json (CLI OAuth)", new CodexModelCatalog(resolver),
                () -> AIProxyOauth.codexModelSource(resolver), () -> null, StartupRenderer.Check.skipped());
    }

    private static StartupRenderer.ProviderStatus anthropicStatus(AnthropicModelResolver resolver) {
        return AIProxyOauth.providerStatus("file: anthropic.json (OAuth)", resolver,
                () -> AIProxyOauth.anthropicModelSource(resolver),
                () -> resolver.lastFailure().map(AnthropicModelResolver.Failure::message).orElse(null), StartupRenderer.Check.skipped());
    }

    private static HttpResponse<InputStream> response(int status, String body) {
        HttpResponse<InputStream> response = mock();
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }
}
