package com.aiproxyoauth.auth;

import java.nio.file.Path;

/** Default directory for proxy-managed logins, separate from external CLI credentials. */
public final class ManagedCredentialPaths {
    private ManagedCredentialPaths() {}

    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".aiproxyoauth");
    }
}
