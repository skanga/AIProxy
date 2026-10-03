package com.aiproxy.protocol.responses;

import com.aiproxy.util.Json;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ResponsesRequestDecoderValidationTest {
    @ParameterizedTest @MethodSource("invalidRequests")
    void rejectsInvalidRequestWithActionableError(String json, String diagnostic) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new ResponsesRequestDecoder().decode(Json.MAPPER.readTree(json), "test-model"));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("null", "JSON object"),
                Arguments.of("[]", "JSON object"),
                Arguments.of("{}", "input"),
                Arguments.of("{\"input\":[]}", "at least one"),
                Arguments.of("{\"input\":3}", "input"),
                Arguments.of("{\"input\":[null]}", "object"),
                Arguments.of("{\"input\":[{\"role\":\"alien\",\"content\":\"hi\"}]}", "role"),
                Arguments.of("{\"input\":[{\"role\":\"user\",\"content\":3}]}", "content"),
                Arguments.of("{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"audio\"}]}]}", "content type"),
                Arguments.of("{\"input\":\"hello\",\"tools\":{}}", "tools"),
                Arguments.of("{\"input\":\"hello\",\"tools\":[{\"type\":\"web_search\"}]}", "function"),
                Arguments.of("{\"input\":\"hello\",\"tool_choice\":\"invalid\"}", "tool_choice"),
                Arguments.of("{\"input\":\"hello\",\"tool_choice\":3}", "tool_choice"),
                Arguments.of("{\"input\":\"hello\",\"stop\":3}", "stop"),
                Arguments.of("{\"input\":\"hello\",\"stop\":[\"ok\",4]}", "stop"),
                Arguments.of("{\"input\":\"hello\",\"temperature\":\"hot\"}", "temperature"),
                Arguments.of("{\"input\":\"hello\",\"top_p\":\"high\"}", "top_p"),
                Arguments.of("{\"input\":\"hello\",\"max_output_tokens\":0}", "positive integer"),
                Arguments.of("{\"input\":\"hello\",\"max_output_tokens\":-1}", "positive integer"),
                Arguments.of("{\"input\":\"hello\",\"max_output_tokens\":1.5}", "positive integer"),
                Arguments.of("{\"input\":\"hello\",\"max_output_tokens\":2147483648}", "positive integer"),
                Arguments.of("{\"input\":\"hello\",\"instructions\":3}", "instructions"),
                Arguments.of("{\"input\":\"hello\",\"reasoning\":[]}", "reasoning"),
                Arguments.of("{\"input\":\"hello\",\"reasoning\":{\"effort\":3}}", "reasoning.effort"),
                Arguments.of("{\"input\":[{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"}]}", "encrypted_content"),
                Arguments.of("{\"input\":[{\"type\":\"reasoning\",\"summary\":\"bad\"}]}", "array"),
                Arguments.of("{\"input\":[{\"type\":\"reasoning\",\"summary\":[{\"text\":\" \"}]}]}", "text"),
                Arguments.of("{\"input\":[{\"type\":\"future_item\"}]}", "input item type"));
    }
}
