package com.aiproxy.provider.copilot;

import com.aiproxy.protocol.chat.ChatStreamDecoder;
import com.aiproxy.protocol.responses.ResponsesStreamDecoder;
import com.aiproxy.provider.spi.CompletionEvent;
import com.aiproxy.provider.spi.CompletionStreamDecoder;
import java.util.List;

/** Supplies Copilot fallback identity and diagnostics to the shared OpenAI decoder. */
public final class CopilotStreamDecoder implements CompletionStreamDecoder {
    private final CompletionStreamDecoder decoder;

    public CopilotStreamDecoder(boolean responses) {
        String error = "Copilot returned a malformed, failed, or incomplete stream";
        decoder = responses ? new ResponsesStreamDecoder("copilot", error) : new ChatStreamDecoder("copilot", error);
    }

    @Override
    public List<CompletionEvent> feed(byte[] bytes) {
        return decoder.feed(bytes);
    }

    @Override
    public List<CompletionEvent> end() {
        return decoder.end();
    }
}
