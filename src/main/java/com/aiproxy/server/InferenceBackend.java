package com.aiproxy.server;

import com.aiproxy.routing.ModelRoute;
import io.javalin.http.Context;

@FunctionalInterface
public interface InferenceBackend {
    void handle(Context context, ModelRoute route, InferenceApi api) throws Exception;
    /** Failover requires known capabilities; older catalogs provide no image/reasoning metadata. */
    default boolean supports(tools.jackson.databind.JsonNode body, ModelRoute route, InferenceApi api) throws Exception {
        return !body.hasNonNull("reasoning") && !body.hasNonNull("reasoning_effort")
                && textAndToolsOnly(body.path(api == InferenceApi.RESPONSES ? "input" : "messages"));
    }

    private static boolean textAndToolsOnly(tools.jackson.databind.JsonNode node) {
        String type = node.path("type").asString();
        if (java.util.Set.of("image_url", "input_image", "image", "input_audio", "audio", "file", "input_file", "reasoning").contains(type)) return false;
        for (var child : node) if (!textAndToolsOnly(child)) return false;
        return true;
    }
}
