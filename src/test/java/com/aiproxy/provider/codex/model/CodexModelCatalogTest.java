package com.aiproxy.provider.codex.model;

import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.codex.CodexHttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class CodexModelCatalogTest {

    @Test
    void adaptsExistingResolverWithoutChangingItsModelIds() throws Exception {
        CodexModelResolver resolver = new CodexModelResolver(
                mock(CodexHttpClient.class),
                List.of("gpt-5.5", "codex-auto-review"),
                "0.121.0"
        );

        CodexModelCatalog catalog = new CodexModelCatalog(resolver);

        assertEquals(ProviderId.CODEX, catalog.provider());
        assertEquals(
                List.of("gpt-5.5", "codex-auto-review"),
                catalog.resolveModels().stream().map(model -> model.id()).toList()
        );
        assertEquals(
                List.of(ProviderId.CODEX, ProviderId.CODEX),
                catalog.resolveModels().stream().map(model -> model.provider()).toList()
        );
    }
}
