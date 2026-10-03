package com.aiproxy.provider.codex.model;

import com.aiproxy.model.ProviderModel;
import com.aiproxy.model.ProviderModelCatalog;
import com.aiproxy.provider.ProviderId;
import java.util.List;
import java.util.Objects;

public final class CodexModelCatalog implements ProviderModelCatalog {

    private final CodexModelResolver resolver;

    public CodexModelCatalog(CodexModelResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public ProviderId provider() {
        return ProviderId.CODEX;
    }

    @Override
    public List<ProviderModel> resolveModels() throws Exception {
        return resolver.resolveProviderModels();
    }
}
