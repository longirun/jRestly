package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ServerSentEvent} and the streaming WHATWG SSE parser
 * (ADR-0016, Step 2).
 */
class ServerSentEventTest {

    @Test
    @DisplayName("record accessors, factories and isDone helper")
    void recordAccessorsAndFactories() {
        ServerSentEvent event = new ServerSentEvent("id-1", "custom", "payload", 2500L);
        assertEquals("id-1", event.id());
        assertEquals("custom", event.event());
        assertEquals("payload", event.data());
        assertEquals(2500L, event.retry());
        assertFalse(event.isDone());

        ServerSentEvent dataOnly = ServerSentEvent.ofData("hello");
        assertNull(dataOnly.id());
        assertNull(dataOnly.event());
        assertEquals("hello", dataOnly.data());
        assertNull(dataOnly.retry());
        assertFalse(dataOnly.isDone());

        ServerSentEvent named = ServerSentEvent.of("greeting", "hi");
        assertNull(named.id());
        assertEquals("greeting", named.event());
        assertEquals("hi", named.data());
        assertNull(named.retry());
        assertFalse(named.isDone());

        ServerSentEvent done = ServerSentEvent.ofData("[DONE]");
        assertTrue(done.isDone());

        ServerSentEvent empty = new ServerSentEvent(null, null, null, null);
        assertFalse(empty.isDone());
    }

    @Test
    @DisplayName("parse single data line")
    void parseSingleDataLine() {
        Stream<String> lines = Stream.of("data: hello world", "");
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertEquals(1, events.size());
        ServerSentEvent event = events.getFirst();
        assertNull(event.id());
        assertNull(event.event());
        assertEquals("hello world", event.data());
        assertNull(event.retry());
    }

    @Test
    @DisplayName("parse multi-line data joined with newline")
    void parseMultiLineData() {
        Stream<String> lines = Stream.of(
                "data: first line",
                "data: second line",
                "data: third line",
                ""
        );
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertEquals(1, events.size());
        assertEquals("first line\nsecond line\nthird line", events.getFirst().data());
    }

    @Test
    @DisplayName("parse all WHATWG SSE fields: id, event, data, retry")
    void parseAllFields() {
        Stream<String> lines = Stream.of(
                "id: evt-42",
                "event: update",
                "retry: 5000",
                "data: {\"status\":\"ok\"}",
                ""
        );
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertEquals(1, events.size());
        ServerSentEvent event = events.getFirst();
        assertEquals("evt-42", event.id());
        assertEquals("update", event.event());
        assertEquals("{\"status\":\"ok\"}", event.data());
        assertEquals(5000L, event.retry());
    }

    @Test
    @DisplayName("colon spacing rules per WHATWG SSE spec")
    void colonSpacingRules() {
        Stream<String> lines = Stream.of(
                "data: with-space",
                "",
                "data:without-space",
                "",
                "data:  two-spaces",
                "",
                "data",
                ""
        );
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertEquals(4, events.size());
        assertEquals("with-space", events.get(0).data());
        assertEquals("without-space", events.get(1).data());
        assertEquals(" two-spaces", events.get(2).data());
        assertEquals("", events.get(3).data());
    }

    @Test
    @DisplayName("ignore comments starting with colon")
    void ignoreComments() {
        Stream<String> lines = Stream.of(
                ": ping keep-alive",
                ":",
                "data: actual payload",
                ": trailing comment",
                ""
        );
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertEquals(1, events.size());
        assertEquals("actual payload", events.getFirst().data());
    }

    @Test
    @DisplayName("ignore unrecognized fields")
    void ignoreUnrecognizedFields() {
        Stream<String> lines = Stream.of(
                "unknownField: some value",
                "data: payload",
                ""
        );
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertEquals(1, events.size());
        assertEquals("payload", events.getFirst().data());
    }

    @Test
    @DisplayName("ignore malformed retry values")
    void ignoreMalformedRetry() {
        Stream<String> lines = Stream.of(
                "retry: not-a-number",
                "data: payload 1",
                "",
                "retry: -500",
                "data: payload 2",
                ""
        );
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertEquals(2, events.size());
        assertNull(events.get(0).retry());
        assertNull(events.get(1).retry());
    }

    @Test
    @DisplayName("empty lines do not produce empty events")
    void emptyLinesDoNotProduceEmptyEvents() {
        Stream<String> lines = Stream.of("", "", "", "");
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("dispatch trailing event when stream ends without trailing empty line")
    void dispatchTrailingEventWithoutEmptyLine() {
        Stream<String> lines = Stream.of(
                "id: 1",
                "data: trailing payload"
        );
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertEquals(1, events.size());
        assertEquals("1", events.getFirst().id());
        assertEquals("trailing payload", events.getFirst().data());
    }

    @Test
    @DisplayName("parse multiple consecutive events resetting fields between dispatches")
    void parseMultipleConsecutiveEvents() {
        Stream<String> lines = Stream.of(
                "id: 1",
                "event: first-type",
                "data: one",
                "",
                "data: two",
                "",
                "id: 3",
                "data: three",
                ""
        );
        List<ServerSentEvent> events = ServerSentEvent.parse(lines).toList();

        assertEquals(3, events.size());

        assertEquals("1", events.get(0).id());
        assertEquals("first-type", events.get(0).event());
        assertEquals("one", events.get(0).data());

        assertNull(events.get(1).id());
        assertNull(events.get(1).event());
        assertEquals("two", events.get(1).data());

        assertEquals("3", events.get(2).id());
        assertNull(events.get(2).event());
        assertEquals("three", events.get(2).data());
    }

    @Test
    @DisplayName("lazy evaluation: upstream lines are read on-demand")
    void lazyEvaluation() {
        List<String> rawLines = List.of(
                "data: event 1",
                "",
                "data: event 2",
                "",
                "data: event 3",
                ""
        );
        AtomicInteger linesRead = new AtomicInteger(0);

        Iterator<String> trackingIterator = new Iterator<>() {
            private final Iterator<String> delegate = rawLines.iterator();

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public String next() {
                linesRead.incrementAndGet();
                return delegate.next();
            }
        };

        Iterable<String> iterable = () -> trackingIterator;
        Stream<String> lineStream = StreamSupport.stream(iterable.spliterator(), false);

        Stream<ServerSentEvent> eventStream = ServerSentEvent.parse(lineStream);

        // Fetch only the first event
        ServerSentEvent first = eventStream.findFirst().orElseThrow();
        assertEquals("event 1", first.data());

        // We only needed 2 lines ("data: event 1", "") to produce the first event!
        assertEquals(2, linesRead.get());
    }

    @Test
    @DisplayName("lifecycle: closing event stream closes underlying line stream")
    void lifecycleOnClose() {
        AtomicBoolean closed = new AtomicBoolean(false);
        Stream<String> lineStream = Stream.of("data: test", "").onClose(() -> closed.set(true));

        Stream<ServerSentEvent> eventStream = ServerSentEvent.parse(lineStream);
        assertFalse(closed.get());

        eventStream.close();
        assertTrue(closed.get());
    }
}
