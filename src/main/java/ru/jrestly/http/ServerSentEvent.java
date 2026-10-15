package ru.jrestly.http;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Immutable model of a Server-Sent Event (SSE) conforming to the WHATWG SSE specification.
 *
 * <p>Commonly used by LLM inference providers and real-time streaming endpoints.
 *
 * <p>The {@code id} and {@code retry} components are stream-level state per WHATWG:
 * they persist across events until overwritten by a later field. The {@code event}
 * component is per-event and reset after each dispatch.
 *
 * @param id the last event identifier (from the {@code id:} field), persisted across events
 *           until overwritten, or {@code null} if the stream never declared one
 * @param event the optional event type (from the {@code event:} field), or {@code null}
 * @param data the event payload (from concatenated {@code data:} lines); dispatched events
 *             always carry a value, which may be an empty string
 * @param retry the currently effective reconnection time in milliseconds (from the
 *              {@code retry:} field), persisted across events until overwritten, or {@code null}
 */
public record ServerSentEvent(String id, String event, String data, Long retry) {

    /**
     * Creates a data-only {@link ServerSentEvent} without id, event type, or retry value.
     *
     * @param data the event payload
     * @return a data-only event
     */
    public static ServerSentEvent ofData(String data) {
        return new ServerSentEvent(null, null, data, null);
    }

    /**
     * Creates a {@link ServerSentEvent} with the specified event name and data payload.
     *
     * @param event the event type name
     * @param data the event payload
     * @return the created event
     */
    public static ServerSentEvent of(String event, String data) {
        return new ServerSentEvent(null, event, data, null);
    }

    /**
     * Checks if this event represents the standard LLM stream termination marker ({@code [DONE]}).
     *
     * @return {@code true} if {@link #data()} equals {@code "[DONE]"}, {@code false} otherwise
     */
    public boolean isDone() {
        return "[DONE]".equals(data);
    }

    /**
     * Parses a lazy stream of raw text lines into a lazy stream of {@link ServerSentEvent}s.
     *
     * @param lines the input line stream (newline terminators stripped)
     * @return a lazy stream of parsed events; closing the stream closes {@code lines}
     */
    public static Stream<ServerSentEvent> parse(Stream<String> lines) {
        Objects.requireNonNull(lines, "lines must not be null");
        return ServerSentEventParser.parse(lines);
    }
}
