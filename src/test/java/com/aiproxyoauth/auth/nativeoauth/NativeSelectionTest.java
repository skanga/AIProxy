package com.aiproxyoauth.auth.nativeoauth;

import com.aiproxyoauth.auth.AuthManager;
import com.aiproxyoauth.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.http.HttpClient;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeSelectionTest {
    @TempDir Path temporary;
    @Test void explicitCliWinsButCorruptNativeWinsOverDiscovery() throws Exception {
        Path nativeFile = temporary.resolve("native.json"), cli = temporary.resolve("cli.json");
        Files.writeString(nativeFile,"broken");
        var overrides = new ConfigOverrides(); overrides.codexNativeAuthFile = nativeFile.toString();
        var config = EffectiveConfigLoader.load(null,Map.of(),overrides);
        assertTrue(CodexAuthSelection.select(config.codex()).nativeProfile());
        overrides.codexOauthFile = cli.toString();
        var selected = CodexAuthSelection.select(EffectiveConfigLoader.load(null,Map.of(),overrides).codex());
        assertFalse(selected.nativeProfile()); assertEquals(cli,selected.path()); assertFalse(selected.available());
        overrides.codexAuthMode="native";
        assertThrows(ConfigException.class,()->EffectiveConfigLoader.load(null,Map.of(),overrides));
    }
    @Test void nativeModeCannotUseCliEndpointsOrStoreResponses() {
        var overrides = new ConfigOverrides(); overrides.codexAuthMode="native";
        overrides.codexNativeAuthFile=temporary.resolve("native.json").toString(); overrides.codexStore=true;
        assertThrows(ConfigException.class,()->CodexAuthSelection.select(EffectiveConfigLoader.load(null,Map.of(),overrides).codex()));
        overrides.codexStore=false; overrides.codexBaseUrl="https://other.invalid";
        assertThrows(ConfigException.class,()->CodexAuthSelection.select(EffectiveConfigLoader.load(null,Map.of(),overrides).codex()));
    }
    @Test void selectedCliFileCannotRediscoverAnotherSource() throws Exception {
        Path file=temporary.resolve("selected.json"); Files.writeString(file,"{broken");
        var config = EffectiveConfigLoader.load(null,Map.of(),new ConfigOverrides()).legacyServerConfig(Map.of(),null);
        var http = mock(HttpClient.class);
        var manager = new AuthManager(config,http,null,file.toString());
        assertThrows(Exception.class,manager::ensureFresh);
        verifyNoInteractions(http);
    }
}
