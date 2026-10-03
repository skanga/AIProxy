package com.aiproxy.provider.codex;

import com.aiproxy.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CodexNativeRequestValidationTest {
    @ParameterizedTest @MethodSource("invalidRequests")
    void rejectsInvalidNativeContract(String body, String diagnostic) throws Exception {
        String error = CodexNativeRequestProfile.validate(Json.MAPPER.readTree(body), false);
        assertNotNull(error);
        assertTrue(error.contains(diagnostic), error);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("{\"store\":true}", "store: false"),
                Arguments.of("{\"store\":\"false\"}", "store: false"),
                Arguments.of("{\"previous_response_id\":3}", "nonempty"),
                Arguments.of("{\"previous_response_id\":\" \"}", "nonempty"),
                Arguments.of("{\"input\":[{\"type\":\"item_reference\",\"id\":\"\"}]}", "ID from local history"),
                Arguments.of("{\"input\":[{\"role\":\"system\"}]}", "developer messages"),
                Arguments.of("{\"tools\":{}}", "array"),
                Arguments.of("{\"tools\":[{\"type\":\"computer_use\"}]}", "tool type"),
                Arguments.of("{\"tools\":[{\"type\":\"function\",\"defer_loading\":true}]}", "deferred"),
                Arguments.of("{\"input\":[{\"type\":\"additional_tools\",\"tools\":[{\"type\":\"computer_use\"}]}]}", "tool type"),
                Arguments.of("{\"tools\":[{\"type\":\"namespace\",\"tools\":[{\"type\":\"computer_use\"}]}]}", "tool type"),
                Arguments.of("{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_audio\"}]}]}", "audio/video"));
    }

    @Test void acceptsSupportedNamespaceToolsAndReplayReferences() throws Exception {
        assertNull(CodexNativeRequestProfile.validate(Json.MAPPER.readTree("""
                {"store":false,"previous_response_id":"local-response",
                 "tools":[{"type":"namespace","name":"files","tools":[{"type":"function","name":"read"}]}],
                 "input":[{"type":"item_reference","id":"local-item"}]}
                """), false));
    }

    @Test void chatRejectsUnsupportedRolesAndContent() throws Exception {
        String role = CodexNativeRequestProfile.validate(Json.MAPPER.readTree("""
                {"messages":[{"role":"alien","content":"hi"}]}
                """), true);
        assertTrue(role.contains("message role"));
        String content = CodexNativeRequestProfile.validate(Json.MAPPER.readTree("""
                {"messages":[{"role":"user","content":[{"type":"input_file"}]}]}
                """), true);
        assertTrue(content.contains("content type"));
    }
}
