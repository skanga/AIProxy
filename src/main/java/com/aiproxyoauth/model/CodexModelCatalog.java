package com.aiproxyoauth.model;

import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;

import java.util.List;
import java.util.Objects;

public final class CodexModelCatalog implements ProviderModelCatalog {

    private final ModelResolver resolver;

    public CodexModelCatalog(ModelResolver resolver) {
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
