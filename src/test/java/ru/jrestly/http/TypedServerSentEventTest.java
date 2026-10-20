package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.codec.PayloadCodec;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedServerSentEventTest {

    record SamplePayload(String name, int score) {}

    private static final PayloadCodec FAKE_CODEC = new PayloadCodec() {
        @Override
        public boolean supports(String mediaType) {
            return mediaType != null && mediaType.contains("json");
        }

        @Override
        public byte[] serialize(Object object, String mediaType) {
            if (object instanceof SamplePayload p) {
                return ("{\"name\":\"" + p.name() + "\",\"score\":" + p.score() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return String.valueOf(object).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T deserialize(byte[] payload, Type targetType, String mediaType) {
            if (payload == null || payload.length == 0) {
                return null;
            }
            String str = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            if (str.contains("\"name\":\"Alice\"")) {
                return (T) new SamplePayload("Alice", 100);
            }
            if (str.contains("\"name\":\"Bob\"")) {
                return (T) new SamplePayload("Bob", 200);
            }
            return (T) new SamplePayload("Unknown", 0);
        }
    };

    @Test
    @DisplayName("factories and getters create valid typed event records")
    void factoriesAndGetters() {
        SamplePayload payload = new SamplePayload("Alice", 100);

        TypedServerSentEvent<SamplePayload> e1 = TypedServerSentEvent.ofData(payload);
        assertNull(e1.id());
        assertNull(e1.event());
        assertEquals(payload, e1.data());
        assertNull(e1.retry());
        assertNull(e1.rawData());
        assertFalse(e1.isDone());

        TypedServerSentEvent<SamplePayload> e2 = TypedServerSentEvent.ofData(payload, "raw");
        assertEquals("raw", e2.rawData());
        assertEquals(payload, e2.data());

        TypedServerSentEvent<SamplePayload> e3 = TypedServerSentEvent.of("greeting", payload);
        assertEquals("greeting", e3.event());
        assertEquals(payload, e3.data());

        TypedServerSentEvent<SamplePayload> e4 = TypedServerSentEvent.of("greeting", payload, "raw-greet");
        assertEquals("greeting", e4.event());
        assertEquals(payload, e4.data());
        assertEquals("raw-greet", e4.rawData());

        TypedServerSentEvent<SamplePayload> full = new TypedServerSentEvent<>("id-1", "ev-1", payload, 5000L, "{\"name\":\"Alice\"}");
        assertEquals("id-1", full.id());
        assertEquals("ev-1", full.event());
        assertEquals(payload, full.data());
        assertEquals(5000L, full.retry());
        assertEquals("{\"name\":\"Alice\"}", full.rawData());

        ServerSentEvent raw = full.toRaw();
        assertEquals("id-1", raw.id());
        assertEquals("ev-1", raw.event());
        assertEquals("{\"name\":\"Alice\"}", raw.data());
        assertEquals(5000L, raw.retry());
    }

    @Test
    @DisplayName("isDone recognizes [DONE] in rawData or data")
    void isDoneRecognition() {
        TypedServerSentEvent<SamplePayload> notDone = TypedServerSentEvent.ofData(new SamplePayload("Alice", 1));
        assertFalse(notDone.isDone());

        TypedServerSentEvent<SamplePayload> doneByRaw = TypedServerSentEvent.ofData(null, "[DONE]");
        assertTrue(doneByRaw.isDone());

        TypedServerSentEvent<String> doneByData = TypedServerSentEvent.ofData("[DONE]");
        assertTrue(doneByData.isDone());
    }

    @Test
    @DisplayName("map transforms payload preserving metadata")
    void mapTransformation() {
        TypedServerSentEvent<SamplePayload> original = new TypedServerSentEvent<>("1", "upd", new SamplePayload("Alice", 10), 3000L, "raw");
        TypedServerSentEvent<String> mapped = original.map(p -> p.name().toUpperCase());

        assertEquals("1", mapped.id());
        assertEquals("upd", mapped.event());
        assertEquals("ALICE", mapped.data());
        assertEquals(3000L, mapped.retry());
        assertEquals("raw", mapped.rawData());

        TypedServerSentEvent<SamplePayload> nullPayload = new TypedServerSentEvent<>("2", "empty", null, null, null);
        TypedServerSentEvent<String> mappedNull = nullPayload.map(SamplePayload::name);
        assertNull(mappedNull.data());

        assertThrows(NullPointerException.class, () -> original.map(null));
    }

    @Test
    @DisplayName("parse lazily converts raw lines into typed events via codec")
    void parseLinesToTypedEvents() {
        Stream<String> lines = Stream.of(
                "event: update",
                "id: 42",
                "data: {\"name\":\"Alice\",\"score\":100}",
                "",
                "data: [DONE]",
                ""
        );

        List<TypedServerSentEvent<SamplePayload>> events = TypedServerSentEvent.parse(lines, FAKE_CODEC, SamplePayload.class).toList();
        assertEquals(2, events.size());

        TypedServerSentEvent<SamplePayload> e1 = events.get(0);
        assertEquals("42", e1.id());
        assertEquals("update", e1.event());
        assertNotNull(e1.data());
        assertEquals("Alice", e1.data().name());
        assertEquals(100, e1.data().score());
        assertEquals("{\"name\":\"Alice\",\"score\":100}", e1.rawData());
        assertFalse(e1.isDone());

        TypedServerSentEvent<SamplePayload> e2 = events.get(1);
        assertEquals("42", e2.id()); // WHATWG id persists across events
        assertNull(e2.event());
        assertNull(e2.data()); // [DONE] does not invoke codec
        assertEquals("[DONE]", e2.rawData());
        assertTrue(e2.isDone());
    }

    @Test
    @DisplayName("parse requires non-null arguments")
    void parseNullChecks() {
        Stream<String> lines = Stream.of("data: hello", "");
        assertThrows(NullPointerException.class, () -> TypedServerSentEvent.parse(null, FAKE_CODEC, SamplePayload.class));
        assertThrows(NullPointerException.class, () -> TypedServerSentEvent.parse(lines, null, SamplePayload.class));
        assertThrows(NullPointerException.class, () -> TypedServerSentEvent.parse(lines, FAKE_CODEC, null));
    }

    @Test
    @DisplayName("ServerSentEvent.toTyped and dataAs deserialization")
    void serverSentEventToTypedAndDataAs() {
        ServerSentEvent raw = new ServerSentEvent("id-10", "ev-chat", "{\"name\":\"Bob\",\"score\":200}", 1500L);

        TypedServerSentEvent<SamplePayload> typed = raw.toTyped(SamplePayload.class, FAKE_CODEC);
        assertEquals("id-10", typed.id());
        assertEquals("ev-chat", typed.event());
        assertEquals("Bob", typed.data().name());
        assertEquals(200, typed.data().score());
        assertEquals(1500L, typed.retry());
        assertEquals("{\"name\":\"Bob\",\"score\":200}", typed.rawData());
        assertFalse(typed.isDone());

        SamplePayload payload = raw.dataAs(SamplePayload.class, FAKE_CODEC);
        assertNotNull(payload);
        assertEquals("Bob", payload.name());

        // Generic Type variant
        TypedServerSentEvent<SamplePayload> typedFromType = raw.toTyped((Type) SamplePayload.class, FAKE_CODEC);
        assertEquals("Bob", typedFromType.data().name());

        SamplePayload payloadFromType = raw.dataAs((Type) SamplePayload.class, FAKE_CODEC);
        assertEquals("Bob", payloadFromType.name());

        // [DONE] marker
        ServerSentEvent doneEvent = ServerSentEvent.ofData("[DONE]");
        TypedServerSentEvent<SamplePayload> typedDone = doneEvent.toTyped(SamplePayload.class, FAKE_CODEC);
        assertNull(typedDone.data());
        assertEquals("[DONE]", typedDone.rawData());
        assertTrue(typedDone.isDone());

        assertNull(doneEvent.dataAs(SamplePayload.class, FAKE_CODEC));

        // null and empty data
        ServerSentEvent emptyEvent = ServerSentEvent.ofData("");
        TypedServerSentEvent<SamplePayload> typedEmpty = emptyEvent.toTyped(SamplePayload.class, FAKE_CODEC);
        assertNull(typedEmpty.data());
        assertNull(emptyEvent.dataAs(SamplePayload.class, FAKE_CODEC));

        ServerSentEvent nullDataEvent = new ServerSentEvent(null, null, null, null);
        TypedServerSentEvent<SamplePayload> typedNull = nullDataEvent.toTyped(SamplePayload.class, FAKE_CODEC);
        assertNull(typedNull.data());
        assertNull(nullDataEvent.dataAs(SamplePayload.class, FAKE_CODEC));

        // null parameter checks
        assertThrows(NullPointerException.class, () -> raw.toTyped((Class<SamplePayload>) null, FAKE_CODEC));
        assertThrows(NullPointerException.class, () -> raw.toTyped(SamplePayload.class, null));
        assertThrows(NullPointerException.class, () -> raw.toTyped((Type) null, FAKE_CODEC));
        assertThrows(NullPointerException.class, () -> raw.toTyped((Type) SamplePayload.class, null));

        assertThrows(NullPointerException.class, () -> raw.dataAs((Class<SamplePayload>) null, FAKE_CODEC));
        assertThrows(NullPointerException.class, () -> raw.dataAs(SamplePayload.class, null));
        assertThrows(NullPointerException.class, () -> raw.dataAs((Type) null, FAKE_CODEC));
        assertThrows(NullPointerException.class, () -> raw.dataAs((Type) SamplePayload.class, null));
    }

    @Test
    @DisplayName("TypedServerSentEvent.reconnecting resumes stream upon IOException with Last-Event-ID")
    void typedReconnecting() {
        AtomicInteger attempts = new AtomicInteger(0);

        Function<String, Stream<TypedServerSentEvent<SamplePayload>>> opener = lastId -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                assertNull(lastId);
                TypedServerSentEvent<SamplePayload> e1 = new TypedServerSentEvent<>("id-1", null, new SamplePayload("Alice", 1), null, "raw-1");
                return Stream.concat(
                        Stream.of(e1),
                        Stream.generate(() -> {
                            throw new UncheckedIOException(new IOException("simulated connection reset"));
                        })
                );
            } else {
                assertEquals("id-1", lastId);
                TypedServerSentEvent<SamplePayload> e2 = new TypedServerSentEvent<>("id-2", null, new SamplePayload("Bob", 2), null, "raw-2");
                TypedServerSentEvent<SamplePayload> done = TypedServerSentEvent.ofData(null, "[DONE]");
                return Stream.of(e2, done);
            }
        };

        ReconnectPolicy policy = ReconnectPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(java.time.Duration.ofMillis(5))
                .build();

        try (Stream<TypedServerSentEvent<SamplePayload>> stream = TypedServerSentEvent.reconnecting(opener, policy)) {
            List<TypedServerSentEvent<SamplePayload>> list = stream.toList();
            assertEquals(3, list.size());
            assertEquals("id-1", list.get(0).id());
            assertEquals("Alice", list.get(0).data().name());
            assertEquals("id-2", list.get(1).id());
            assertEquals("Bob", list.get(1).data().name());
            assertTrue(list.get(2).isDone());
        }

        assertEquals(2, attempts.get());

        assertThrows(NullPointerException.class, () -> TypedServerSentEvent.reconnecting(null, policy));
        assertThrows(NullPointerException.class, () -> TypedServerSentEvent.reconnecting(opener, null));
    }
}
