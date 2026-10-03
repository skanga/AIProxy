package com.aiproxy.server;

/** Client protocol selected by route registration, independent of the request URL. */
public enum InferenceApi {
    CHAT_COMPLETIONS, RESPONSES
}
