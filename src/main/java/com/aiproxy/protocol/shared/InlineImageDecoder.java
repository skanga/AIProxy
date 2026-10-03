package com.aiproxy.protocol.shared;

import com.aiproxy.provider.spi.ChatRequest;
import java.util.Base64;

/** Decodes the inline image representation shared by Chat and Responses requests. */
public final class InlineImageDecoder {
    private static final int MAX_ENCODED_IMAGE_CHARACTERS = 7 * 1024 * 1024;
    private InlineImageDecoder() {}

    public static ChatRequest.Image decode(String url) {
        if (url == null || !url.startsWith("data:image/")) {
            throw new IllegalArgumentException("Image inputs must use an inline image data URL");
        }
        int semicolon = url.indexOf(';');
        int comma = url.indexOf(',');
        if (semicolon < 0 || comma < 0 || semicolon > comma
                || !url.substring(semicolon, comma).equals(";base64")) {
            throw new IllegalArgumentException("Image data URL must be base64 encoded");
        }
        String encoded = url.substring(comma + 1);
        if (encoded.length() > MAX_ENCODED_IMAGE_CHARACTERS) {
            throw new IllegalArgumentException("Image data URL exceeds the size limit");
        }
        try {
            return new ChatRequest.Image(
                    url.substring(5, semicolon), Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Image data URL contains invalid base64");
        }
    }

}
