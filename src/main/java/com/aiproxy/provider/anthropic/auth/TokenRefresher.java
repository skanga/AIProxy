package com.aiproxy.provider.anthropic.auth;

import java.io.IOException;

@FunctionalInterface
interface TokenRefresher {
    OAuthTokenSet refresh(String refreshToken) throws IOException;
}
