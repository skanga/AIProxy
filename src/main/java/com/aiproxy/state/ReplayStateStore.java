package com.aiproxy.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded namespace storage. Each backend owns its own instance and supplies its identity key. */
public final class ReplayStateStore {
    private final Map<String, ResponsesState> states;

    public ReplayStateStore(int maximumNamespaces) {
        if (maximumNamespaces < 1) throw new IllegalArgumentException("maximumNamespaces must be positive");
        states = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, ResponsesState> entry) {
                return size() > maximumNamespaces;
            }
        };
    }

    public synchronized ResponsesState forNamespace(String namespace) {
        return states.computeIfAbsent(Objects.requireNonNull(namespace, "namespace"), ignored -> new ResponsesState());
    }
}
