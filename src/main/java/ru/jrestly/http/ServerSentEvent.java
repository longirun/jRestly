package ru.jrestly.http;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import ru.jrestly.codec.PayloadCodec;

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
public record ServerSentEvent(String id, String event, String data, Long retry) implements SseEventContract {

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
    @Override
    public boolean isDone() {
        return "[DONE]".equals(data);
    }

    /**
     * Converts this untyped event into a {@link TypedServerSentEvent} using the provided
     * codec to deserialize the payload into the given target class.
     *
     * @param <T> the target payload type
     * @param type the target payload class
     * @param codec the codec for deserialization
     * @return the typed event
     */
    public <T> TypedServerSentEvent<T> toTyped(Class<T> type, PayloadCodec codec) {
        return toTyped((Type) type, codec);
    }

    /**
     * Converts this untyped event into a {@link TypedServerSentEvent} using the provided
     * codec to deserialize the payload into the given target generic type.
     *
     * @param <T> the target payload type
     * @param type the target generic payload type
     * @param codec the codec for deserialization
     * @return the typed event
     */
    public <T> TypedServerSentEvent<T> toTyped(Type type, PayloadCodec codec) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(codec, "codec must not be null");
        if (isDone()) {
            return new TypedServerSentEvent<>(id, event, null, retry, data);
        }
        byte[] bytes = (data != null && !data.isEmpty()) ? data.getBytes(StandardCharsets.UTF_8) : null;
        T parsed = (bytes != null && bytes.length > 0) ? codec.deserialize(bytes, type, "application/json") : null;
        return new TypedServerSentEvent<>(id, event, parsed, retry, data);
    }

    /**
     * Deserializes the data payload into the specified class using the given codec.
     *
     * @param <T> the target payload type
     * @param type the target payload class
     * @param codec the codec for deserialization
     * @return the deserialized object, or {@code null} if data is empty or represents {@code [DONE]}
     */
    public <T> T dataAs(Class<T> type, PayloadCodec codec) {
        return dataAs((Type) type, codec);
    }

    /**
     * Deserializes the data payload into the specified generic type using the given codec.
     *
     * @param <T> the target payload type
     * @param type the target generic payload type
     * @param codec the codec for deserialization
     * @return the deserialized object, or {@code null} if data is empty or represents {@code [DONE]}
     */
    public <T> T dataAs(Type type, PayloadCodec codec) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(codec, "codec must not be null");
        if (data == null || data.isEmpty() || isDone()) {
            return null;
        }
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        return (bytes.length > 0) ? codec.deserialize(bytes, type, "application/json") : null;
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

    /**
     * Wraps an SSE connection factory into a lazy stream that reconnects per
     * {@link ReconnectPolicy} (ADR-0059).
     *
     * <p>Each returned connection is consumed event by event; a mid-stream
     * {@link java.io.UncheckedIOException}, a failing opener call, or a clean EOF
     * opens the next connection with the last seen event id passed back to the
     * {@code opener} — the canonical use is a nullable
     * {@code @RequestHeader("Last-Event-ID")} parameter:
     *
     * <pre>{@code
     * Stream<ServerSentEvent> events = ServerSentEvent.reconnecting(
     *         lastEventId -> api.streamChat(body, lastEventId),
     *         ReconnectPolicy.builder().maxAttempts(5).build());
     * }</pre>
     *
     * <p>The stream is lazy: the opener runs on the consumer's thread, on the
     * first pull and on every reconnect. The previous connection's stream is
     * closed before the wait; closing the returned stream closes the current
     * connection and cancels reconnection. Failures other than
     * {@code UncheckedIOException} propagate without a reconnect.
     *
     * <p>Honest contract: a server that does not honor {@code Last-Event-ID}
     * may replay events after a reconnect — that is protocol behavior, not a
     * library guarantee.
     *
     * @param opener the connection factory receiving the nullable
     *               {@code Last-Event-ID} value (the id of the last seen event,
     *               or {@code null} on the first connection); must return a
     *               fresh non-null stream
     * @param policy the reconnection policy ({@link ReconnectPolicy#defaults()}
     *               for WHATWG defaults with a 3-attempt bound)
     * @return a lazy concatenation of the events of successive connections
     * @throws NullPointerException if {@code opener} or {@code policy} is {@code null}
     */
    public static Stream<ServerSentEvent> reconnecting(
            Function<String, Stream<ServerSentEvent>> opener, ReconnectPolicy policy) {
        Objects.requireNonNull(opener, "opener must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        return ServerSentEventReconnector.reconnecting(opener, policy);
    }
}
