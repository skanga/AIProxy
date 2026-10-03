package com.aiproxy.protocol.responses;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

class ResponsesStreamCollectorTest {
    @Test
    void collectCompletedResponse_success() throws Exception {
        String data = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-1\"}}\n\n";
        InputStream is = new ByteArrayInputStream(data.getBytes());
        JsonNode response = ResponsesStreamCollector.collectCompletedResponse(is);
        
        assertEquals("resp-1", response.get("id").asString());
    }

    @Test
    void collectCompletedResponse_noResponse_throwsException() {
        String data = "data: {\"type\":\"other\"}\n\n";
        InputStream is = new ByteArrayInputStream(data.getBytes());
        assertThrows(IOException.class, () -> ResponsesStreamCollector.collectCompletedResponse(is));
    }

    @Test
    void collectCompletedResponse_recoversFunctionCallFromStreamEvents() throws Exception {
        String data = """
                data: {"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"python-eval","arguments":""}}

                data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\\"code\\":"}

                data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\\"factorial(100)\\"}"}

                data: {"type":"response.completed","response":{"status":"completed","output":[],"usage":{"output_tokens":27}}}

                """;

        JsonNode response = ResponsesStreamCollector.collectCompletedResponse(
                new ByteArrayInputStream(data.getBytes()));

        JsonNode call = response.path("output").get(0);
        assertEquals("function_call", call.path("type").asString());
        assertEquals("call_1", call.path("call_id").asString());
        assertEquals("python-eval", call.path("name").asString());
        assertEquals("{\"code\":\"factorial(100)\"}", call.path("arguments").asString());
    }

    @Test
    void collectCompletedResponse_doesNotDuplicateFunctionCallAlreadyInCompletedOutput() throws Exception {
        String data = """
                data: {"type":"response.output_item.added","item":{"type":"function_call","call_id":"call_1","name":"fn","arguments":""}}

                data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_1","name":"fn","arguments":"{}"}]}}

                """;

        JsonNode response = ResponsesStreamCollector.collectCompletedResponse(
                new ByteArrayInputStream(data.getBytes()));

        assertEquals(1, response.path("output").size());
        assertEquals("{}", response.path("output").get(0).path("arguments").asString());
    }
}
