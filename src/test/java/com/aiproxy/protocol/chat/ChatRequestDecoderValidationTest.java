package com.aiproxy.protocol.chat;

import com.aiproxy.util.Json;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ChatRequestDecoderValidationTest {
    @ParameterizedTest @MethodSource("invalidRequests")
    void rejectsInvalidRequestWithActionableError(String json, String diagnostic) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new ChatRequestDecoder().decode(Json.MAPPER.readTree(json), "test-model"));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("null", "JSON object"),
                Arguments.of("[]", "JSON object"),
                Arguments.of("{}", "messages"),
                Arguments.of("{\"messages\":[]}", "empty"),
                Arguments.of("{\"messages\":3}", "messages"),
                Arguments.of("{\"messages\":[null]}", "object"),
                Arguments.of("{\"messages\":[{\"role\":\"alien\",\"content\":\"hi\"}]}", "role"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":3}]}", "content"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"audio\"}]}]}", "content type"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"tools\":{}}", "tools"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"tools\":[{\"type\":\"web_search\"}]}", "function"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"tool_choice\":\"invalid\"}", "tool_choice"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"tool_choice\":3}", "tool_choice"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stop\":3}", "stop"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stop\":[\"ok\",4]}", "stop"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"temperature\":\"hot\"}", "temperature"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"top_p\":\"high\"}", "top_p"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":0}", "positive integer"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":-1}", "positive integer"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":1.5}", "positive integer"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":2147483648}", "positive integer"),
                Arguments.of("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"reasoning_effort\":3}", "reasoning_effort"),
                Arguments.of("{\"messages\":[{\"role\":\"assistant\",\"tool_calls\":{}}]}", "tool_calls"),
                Arguments.of("{\"messages\":[{\"role\":\"tool\",\"content\":\"result\"}]}", "tool_call_id"),
                Arguments.of("{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":[{\"type\":\"image_url\"}]}]}", "only text"),
                Arguments.of("{\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"f\",\"arguments\":\" \"}}]}]}", "arguments"));
    }
}
