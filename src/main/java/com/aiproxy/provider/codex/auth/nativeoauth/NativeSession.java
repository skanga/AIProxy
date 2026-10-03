package com.aiproxy.provider.codex.auth.nativeoauth;

import java.io.IOException;
import java.time.Clock;

/** Pins the selected login for this server. Every request observes logout and concurrent refresh. */
public final class NativeSession {
    private final NativeCredentialStore store;
    private final NativeOAuth oauth;
    private final Clock clock;
    private String identity;
    public NativeSession(NativeCredentialStore store, NativeOAuth oauth, Clock clock) {
        this.store = store; this.oauth = oauth; this.clock = clock;
    }
    public NativeCredential current() throws Exception {
        return store.locked(() -> {
            NativeCredential current = store.read();
            if (current == null) throw new IOException("Native Codex credentials not found; run auth codex login and restart the proxy");
            String selected = current.clientId() + "/" + current.subject() + "/" + current.sessionId();
            if (identity == null) identity = selected;
            else if (!identity.equals(selected)) throw new IOException("Native Codex login changed; restart the proxy");
            long now = clock.instant().getEpochSecond();
            if (current.expiresAt() <= now + 300 && now >= current.earliestRefreshAt()) {
                current = oauth.refresh(current);
                store.save(current); // Never use rotated tokens until durable persistence succeeds.
            }
            if (current.expiresAt() <= now) throw new IOException("Native Codex access expired; sign in again");
            return current;
        });
    }
    public String identity() throws Exception { return current().sessionId(); }
    public String source() { return store.path().toString(); }
}
