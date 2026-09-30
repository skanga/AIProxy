package com.aiproxyoauth.auth.nativeoauth;

import com.aiproxyoauth.auth.AuthFileResolver;
import com.aiproxyoauth.config.ConfigException;
import com.aiproxyoauth.config.EffectiveConfig;
import com.aiproxyoauth.config.ServerConfig;
import java.nio.file.Files;
import java.nio.file.Path;

/** Select once, before reading credentials. A broken selected source never falls through. */
public record CodexAuthSelection(boolean nativeProfile, Path path) {
    public static CodexAuthSelection select(EffectiveConfig.Codex config) {
        boolean nativeProfile = config.authMode() == EffectiveConfig.CodexAuthMode.NATIVE
                || (config.authMode() == EffectiveConfig.CodexAuthMode.AUTO && config.oauthFile() == null
                    && Files.exists(config.nativeAuthFile()));
        if (nativeProfile) {
            if (config.store() || config.forwardPromptCacheHeaders()
                    || !ServerConfig.DEFAULT_BASE_URL.equals(config.baseUrl())
                    || !ServerConfig.DEFAULT_CLIENT_ID.equals(config.oauthClientId())
                    || config.oauthTokenUrl() != null || config.version() != null)
                throw new ConfigException("Native Codex auth conflicts with CLI endpoint/version/header settings or codex.store=true");
            return new CodexAuthSelection(true, config.nativeAuthFile());
        }
        if (config.oauthFile() != null) return new CodexAuthSelection(false, config.oauthFile());
        for (String value : AuthFileResolver.resolveCandidates(null)) {
            Path candidate = Path.of(value);
            if (Files.exists(candidate)) return new CodexAuthSelection(false, candidate);
        }
        return new CodexAuthSelection(false, null);
    }
    public boolean available() { return path != null && Files.exists(path); }
}
