package com.aiproxy.util;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test
    void mapperIsAvailable() {
        assertNotNull(Json.MAPPER);
    }

    @Test
    void canParseJson() throws Exception {
        String json = "{\"key\":\"value\"}";
        JsonNode node = Json.MAPPER.readTree(json);
        assertEquals("value", node.get("key").asString());
    }
}
