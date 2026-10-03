package com.aiproxy.protocol.messages;

import com.aiproxy.provider.spi.BlockType;
import com.aiproxy.provider.spi.CompletionEvent;
import com.aiproxy.provider.spi.FinishReason;
import com.aiproxy.provider.spi.ProviderError;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesStreamDecoderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void arbitraryByteSplitsPreserveCompleteCanonicalLifecycle() {
        String wire = event("message_start", """
                {"message":{"id":"msg_1","model":"claude-sonnet-4-5",
                "usage":{"input_tokens":5,"output_tokens":0,
                "cache_creation_input_tokens":2,"cache_read_input_tokens":3}}}
                """)
                + event("ping", "{}")
                + event("content_block_start", """
                {"index":0,"content_block":{"type":"text","text":""}}
                """)
                + event("content_block_delta", """
                {"index":0,"delta":{"type":"text_delta","text":"A🙂"}}
                """)
                + event("content_block_stop", "{\"index\":0}")
                + event("content_block_start", """
                {"index":1,"content_block":{"type":"thinking","thinking":""}}
                """)
                + event("content_block_delta", """
                {"index":1,"delta":{"type":"thinking_delta","thinking":"plan"}}
                """)
                + event("content_block_delta", """
                {"index":1,"delta":{"type":"signature_delta","signature":"signed"}}
                """)
                + event("content_block_stop", "{\"index\":1}")
                + event("content_block_start", """
                {"index":2,"content_block":{"type":"tool_use","id":"tool-1",
                "name":"read","input":{}}}
                """)
                + event("content_block_delta", """
                {"index":2,"delta":{"type":"input_json_delta","partial_json":"{\\\"path\\\":"}}
                """)
                + event("content_block_delta", """
                {"index":2,"delta":{"type":"input_json_delta","partial_json":"\\\"x\\\"}"}}
                """)
                + event("content_block_stop", "{\"index\":2}")
                + event("message_delta", """
                {"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}
                """)
                + event("message_stop", "{}");
        MessagesStreamDecoder decoder = new MessagesStreamDecoder(CLOCK);
        List<CompletionEvent> events = new ArrayList<>();

        for (byte value : wire.getBytes(StandardCharsets.UTF_8)) {
            events.addAll(decoder.feed(new byte[]{value}));
        }
        events.addAll(decoder.end());

        assertEquals(List.of(
                new CompletionEvent.Started(
                        "msg_1", "claude-sonnet-4-5", CLOCK.instant().getEpochSecond()),
                new CompletionEvent.UsageSnapshot(10, 0, 2, 3),
                new CompletionEvent.Heartbeat(),
                new CompletionEvent.BlockStarted(0, BlockType.TEXT, null, null),
                new CompletionEvent.TextDelta(0, "A🙂"),
                new CompletionEvent.BlockFinished(0),
                new CompletionEvent.BlockStarted(1, BlockType.REASONING, null, null),
                new CompletionEvent.ReasoningDelta(1, "plan"),
                new CompletionEvent.ReasoningSignature(1, "signed"),
                new CompletionEvent.BlockFinished(1),
                new CompletionEvent.BlockStarted(
                        2, BlockType.TOOL_CALL, "tool-1", "read"),
                new CompletionEvent.ToolCallArgumentsDelta(2, "{\"path\":"),
                new CompletionEvent.ToolCallArgumentsDelta(2, "\"x\"}"),
                new CompletionEvent.BlockFinished(2),
                new CompletionEvent.UsageSnapshot(10, 7, 2, 3),
                new CompletionEvent.Finished(FinishReason.TOOL_CALLS)
        ), events);
        assertTrue(decoder.end().isEmpty());
    }

    @Test
    void decodesRedactedThinkingWithoutExposingItAsText() {
        MessagesStreamDecoder decoder = new MessagesStreamDecoder(CLOCK);

        List<CompletionEvent> events = decoder.feed(bytes(
                start()
                        + event("content_block_start", """
                        {"index":0,"content_block":{"type":"redacted_thinking",
                        "data":{"opaque":"value"}}}
                        """)
                        + event("content_block_stop", "{\"index\":0}")
                        + event("message_stop", "{}")
        ));

        assertEquals(BlockType.REDACTED_REASONING,
                ((CompletionEvent.BlockStarted) events.get(1)).type());
        assertEquals("value",
                ((CompletionEvent.RedactedReasoning) events.get(2))
                        .data().path("opaque").asString());
    }

    @Test
    void malformedKnownEventAndTruncatedStreamProduceOneProtocolTerminal() {
        MessagesStreamDecoder malformed = new MessagesStreamDecoder(CLOCK);
        List<CompletionEvent> malformedEvents = malformed.feed(bytes(
                event("content_block_delta", "{bad}")
                        + event("message_stop", "{}")
        ));

        assertEquals(1, malformedEvents.size());
        assertEquals(
                ProviderError.Kind.PROTOCOL,
                ((CompletionEvent.Error) malformedEvents.getFirst()).error().kind()
        );
        assertTrue(malformed.end().isEmpty());

        MessagesStreamDecoder truncated = new MessagesStreamDecoder(CLOCK);
        truncated.feed(bytes(event("message_start", """
                {"message":{"id":"msg","model":"claude","usage":{}}}
                """)));
        List<CompletionEvent> end = truncated.end();
        assertEquals(1, end.size());
        assertEquals(
                ProviderError.Kind.PROTOCOL,
                ((CompletionEvent.Error) end.getFirst()).error().kind()
        );
        assertTrue(truncated.end().isEmpty());
    }

    @Test
    void oversizedUnterminatedFrameFailsBoundedly() {
        MessagesStreamDecoder decoder = new MessagesStreamDecoder(CLOCK, 1024);

        List<CompletionEvent> events =
                decoder.feed(("data: " + "x".repeat(2048)).getBytes(StandardCharsets.UTF_8));

        assertEquals(1, events.size());
        assertEquals(
                ProviderError.Kind.PROTOCOL,
                ((CompletionEvent.Error) events.getFirst()).error().kind()
        );
    }

    @Test
    void invalidUtf8AndTypedUpstreamErrorsAreTerminal() {
        MessagesStreamDecoder invalidUtf8 = new MessagesStreamDecoder(CLOCK);
        List<CompletionEvent> invalidEvents =
                invalidUtf8.feed(new byte[]{'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, '\n', '\n'});
        assertEquals(
                ProviderError.Kind.PROTOCOL,
                ((CompletionEvent.Error) invalidEvents.getFirst()).error().kind()
        );

        MessagesStreamDecoder overloaded = new MessagesStreamDecoder(CLOCK);
        List<CompletionEvent> errorEvents = overloaded.feed(bytes(event("error", """
                {"type":"error","error":{"type":"overloaded_error","message":"busy"}}
                """)));
        CompletionEvent.Error error = (CompletionEvent.Error) errorEvents.getFirst();
        assertEquals(ProviderError.Kind.OVERLOADED, error.error().kind());
        assertEquals(529, error.error().httpStatus());
        assertTrue(overloaded.end().isEmpty());
    }

    @Test
    void mapsEveryStopReason() {
        assertEquals(FinishReason.STOP, finish("end_turn"));
        assertEquals(FinishReason.STOP, finish("stop_sequence"));
        assertEquals(FinishReason.TOOL_CALLS, finish("tool_use"));
        assertEquals(FinishReason.LENGTH, finish("max_tokens"));
        assertEquals(FinishReason.UNSPECIFIED, finish("future_reason"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "duplicate-message", "duplicate-block", "unopened-delta", "unopened-stop",
            "wrong-delta-type", "open-block-at-stop", "nonobject", "negative-index", "missing-redaction"})
    void invalidLifecycleProducesExactlyOneTerminalError(String scenario) {
        String block = event("content_block_start",
                "{\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        String invalid = switch (scenario) {
            case "duplicate-message" -> start();
            case "duplicate-block" -> block + block;
            case "unopened-delta" -> event("content_block_delta",
                    "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}}");
            case "unopened-stop" -> event("content_block_stop", "{\"index\":0}");
            case "wrong-delta-type" -> block + event("content_block_delta",
                    "{\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"x\"}}");
            case "open-block-at-stop" -> block + event("message_stop", "{}");
            case "nonobject" -> event("message_delta", "[]");
            case "negative-index" -> event("content_block_start",
                    "{\"index\":-1,\"content_block\":{\"type\":\"text\"}}");
            case "missing-redaction" -> event("content_block_start",
                    "{\"index\":0,\"content_block\":{\"type\":\"redacted_thinking\"}}");
            default -> throw new AssertionError(scenario);
        };
        var decoder = new MessagesStreamDecoder(CLOCK);
        List<CompletionEvent> events = decoder.feed(bytes(start() + invalid + event("message_stop", "{}")));
        assertEquals(1, events.stream().filter(CompletionEvent.Error.class::isInstance).count());
        assertEquals(0, events.stream().filter(CompletionEvent.Finished.class::isInstance).count());
        assertEquals(ProviderError.Kind.PROTOCOL, ((CompletionEvent.Error) events.getLast()).error().kind());
        assertTrue(decoder.end().isEmpty());
        assertTrue(decoder.feed(bytes(start())).isEmpty());
    }

    @Test void incompleteFinalFrameFailsOnceAtEnd() {
        var decoder = new MessagesStreamDecoder(CLOCK);
        decoder.feed(bytes(start() + "event: message_stop\ndata: {}"));
        var events = decoder.end();
        assertEquals(1, events.size());
        assertEquals(ProviderError.Kind.PROTOCOL, ((CompletionEvent.Error) events.getFirst()).error().kind());
        assertTrue(decoder.end().isEmpty());
    }

    @Test void unknownEventsAndBlocksAreIgnoredWithoutLosingCompletion() {
        var decoder = new MessagesStreamDecoder(CLOCK);
        var events = decoder.feed(bytes(start()
                + event("future_event", "{not-json}")
                + event("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"future_block\"}}")
                + event("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"future_delta\"}}")
                + event("content_block_stop", "{\"index\":0}")
                + event("message_stop", "{}")));
        assertEquals(2, events.size());
        assertTrue(events.getFirst() instanceof CompletionEvent.Started);
        assertTrue(events.getLast() instanceof CompletionEvent.Finished);
        assertTrue(decoder.end().isEmpty());
    }

    private static FinishReason finish(String reason) {
        MessagesStreamDecoder decoder = new MessagesStreamDecoder(CLOCK);
        List<CompletionEvent> events = decoder.feed(bytes(
                start() + event("message_delta",
                        "{\"delta\":{\"stop_reason\":\"" + reason + "\"}}")
                        + event("message_stop", "{}")
        ));
        return ((CompletionEvent.Finished) events.getLast()).reason();
    }

    private static String event(String name, String data) {
        String payload = data.strip().lines()
                .map(line -> "data: " + line)
                .collect(java.util.stream.Collectors.joining("\n"));
        return "event: " + name + "\n" + payload + "\n\n";
    }

    private static String start() {
        return event("message_start", """
                {"message":{"id":"msg","model":"claude"}}
                """);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
