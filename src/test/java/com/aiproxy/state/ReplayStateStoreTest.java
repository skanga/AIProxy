package com.aiproxy.state;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayStateStoreTest {
    @Test
    void preservesRecentlyUsedNamespacesAndIsolatesAccountsAndStores() {
        var store = new ReplayStateStore(2);
        var first = store.forNamespace("client:account-one");
        var second = store.forNamespace("client:account-two");
        assertNotSame(first, second);
        assertSame(first, store.forNamespace("client:account-one"));
        store.forNamespace("another-client:account-one");
        assertSame(first, store.forNamespace("client:account-one"));
        assertNotSame(second, store.forNamespace("client:account-two"));
        assertNotSame(first, new ReplayStateStore(2).forNamespace("client:account-one"));
    }

    @Test
    void concurrentRequestsShareOneNamespaceState() throws Exception {
        var store = new ReplayStateStore(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<ResponsesState>>();
            for (int i = 0; i < 40; i++) tasks.add(() -> store.forNamespace("client:account"));
            var results = executor.invokeAll(tasks);
            var expected = results.getFirst().get();
            for (var result : results) assertSame(expected, result.get());
        }
    }
}
