package com.aiproxy.provider.copilot;

import com.aiproxy.protocol.messages.MessagesRequestEncoder;
import com.aiproxy.protocol.chat.ChatRequestEncoder;
import com.aiproxy.protocol.responses.ResponsesRequestEncoder;
import com.aiproxy.provider.spi.ChatRequestValidation;
import com.aiproxy.provider.spi.ChatRequest;
import tools.jackson.databind.node.ObjectNode;

/** Selects the catalog's protocol and applies Copilot's upstream request policy. */
public final class CopilotRequestEncoder {
    private CopilotRequestEncoder() {}

    public static ObjectNode encode(ChatRequest request, String endpoint) throws Exception {
        ChatRequestValidation.validateToolChoice(request);
        if (endpoint.equals("/v1/messages")) {
            ObjectNode root = new MessagesRequestEncoder().encode(request);
            root.put("stream", true);
            if (root.path("system").isEmpty()) root.remove("system");
            return root;
        }
        boolean responses = endpoint.equals("/responses");
        if (responses && !request.stopSequences().isEmpty()) {
            throw new IllegalArgumentException("stop is unsupported by the Copilot Responses protocol");
        }
        for (var message : request.messages()) {
            for (var part : message.content()) {
                if (part instanceof ChatRequest.Reasoning reasoning
                        && (!reasoning.signature().isEmpty() || reasoning.redactedData() != null)) {
                    throw new IllegalArgumentException("Provider-specific reasoning state cannot be translated to this Copilot protocol");
                }
            }
        }
        ObjectNode root = responses ? ResponsesRequestEncoder.encode(request) : ChatRequestEncoder.encode(request);
        root.put("stream", true);
        if (responses) root.put("store", false);
        else root.putObject("stream_options").put("include_usage", true);
        return root;
    }
}
