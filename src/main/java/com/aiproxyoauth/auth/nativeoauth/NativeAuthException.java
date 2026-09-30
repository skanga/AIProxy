package com.aiproxyoauth.auth.nativeoauth;

/** Safe public error; OAuth responses and credential contents must never reach diagnostics. */
public final class NativeAuthException extends RuntimeException {
    public NativeAuthException() {
        super("Native Codex credentials are unavailable, expired, or changed. Run auth codex login if needed and restart the proxy.");
    }
}
