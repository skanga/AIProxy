package com.aiproxy.provider.spi;

import com.aiproxy.protocol.chat.ChatRequestDecoder;
import com.aiproxy.protocol.responses.ResponsesRequestDecoder;
import com.aiproxy.util.Json;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationParametersTest {
    @Test void omissionAndNullRemainUnspecified() throws Exception {
        var chat = new ChatRequestDecoder().decode(Json.MAPPER.readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":null,\"reasoning_effort\":null}"), "test");
        assertNull(chat.maxOutputTokens());
        assertNull(chat.reasoningEffort());
        var responses = new ResponsesRequestDecoder().decode(Json.MAPPER.readTree("{\"input\":\"hi\",\"max_output_tokens\":null}"), "test");
        assertNull(responses.maxOutputTokens());
    }

    @Test void equalAliasesAreAcceptedButBothMustBeValid() throws Exception {
        assertEquals(10, GenerationParameters.tokenLimit(Json.MAPPER.readTree("{\"max_tokens\":10,\"max_completion_tokens\":10}"), false));
        for (String legacy : new String[]{"0", "-1", "1.5", "2147483648", "\"10\"", "true", "9"}) {
            var body = Json.MAPPER.readTree("{\"max_tokens\":" + legacy + ",\"max_completion_tokens\":10}");
            assertThrows(GenerationParameters.ParameterException.class, () -> GenerationParameters.validate(body, false));
        }
    }

    @Test void invalidReasoningNeverBecomesAnOmittedSetting() throws Exception {
        for (String effort : new String[]{"\"\"", "\" \"", "\"bogus\"", "42", "[]"}) {
            var body = Json.MAPPER.readTree("{\"reasoning_effort\":" + effort + "}");
            assertThrows(GenerationParameters.ParameterException.class, () -> GenerationParameters.validate(body, false));
        }
        assertEquals("none", GenerationParameters.effort(Json.MAPPER.readTree("{\"reasoning_effort\":\"none\"}"), false));
        assertEquals("xhigh", GenerationParameters.effort(Json.MAPPER.readTree("{\"reasoning\":{\"effort\":\"xhigh\"}}"), true));
    }
}
