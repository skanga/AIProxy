package com.aiproxy.model;

import java.util.List;

public interface ModelCatalog {

    List<ProviderModel> resolveModels() throws Exception;
}
