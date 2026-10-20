package ru.jrestly.http;

import java.lang.reflect.Type;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import ru.jrestly.codec.PayloadCodec;

/**
 * Immutable typed model of a Server-Sent Event (SSE) with its data payload deserialized
 * via a {@link PayloadCodec}.
 *
 * <p>Commonly used for real-time AI/LLM streaming inference (OpenAI, Anthropic, Ollama, DeepSeek)
 * and event-driven microservices emitting structured JSON chunks.
 *
 * <p>Preserves full protocol conformance with the WHATWG SSE specification while eliminating
 * manual string-to-DTO decoding and boilerplate error handling around stream terminators
 * like {@code [DONE]}.
 *
 * @param <T> the deserialized data payload type
 * @param id the last event identifier (from the {@code id:} field), persisted across events
 *           until overwritten, or {@code null} if the stream never declared one
 * @param event the optional event type (from the {@code event:} field), or {@code null}
 * @param data the deserialized event payload, or {@code null} if the payload was empty or represented
 *             a non-payload stream marker such as {@code [DONE]}
 * @param retry the currently effective reconnection time in milliseconds, or {@code null}
 * @param rawData the original raw string representation of the data lines, or {@code null}
 */
public record TypedServerSentEvent<T>(
        String id,
        String event,
        T data,
        Long retry,
        String rawData
) implements SseEventContract {

    /**
     * Creates a data-only {@link TypedServerSentEvent} with the given payload.
     *
     * @param <T> the payload type
     * @param data the deserialized payload
     * @return a data-only typed event
     */
    public static <T> TypedServerSentEvent<T> ofData(T data) {
        return new TypedServerSentEvent<>(null, null, data, null, null);
    }

    /**
     * Creates a data-only {@link TypedServerSentEvent} with both deserialized payload and raw string.
     *
     * @param <T> the payload type
     * @param data the deserialized payload
     * @param rawData the raw string payload
     * @return a data-only typed event
     */
    public static <T> TypedServerSentEvent<T> ofData(T data, String rawData) {
        return new TypedServerSentEvent<>(null, null, data, null, rawData);
    }

    /**
     * Creates a {@link TypedServerSentEvent} with the specified event name and data payload.
     *
     * @param <T> the payload type
     * @param event the event type name
     * @param data the deserialized payload
     * @return the created event
     */
    public static <T> TypedServerSentEvent<T> of(String event, T data) {
        return new TypedServerSentEvent<>(null, event, data, null, null);
    }

    /**
     * Creates a {@link TypedServerSentEvent} with the specified event name, deserialized payload, and raw string.
     *
     * @param <T> the payload type
     * @param event the event type name
     * @param data the deserialized payload
     * @param rawData the raw string payload
     * @return the created event
     */
    public static <T> TypedServerSentEvent<T> of(String event, T data, String rawData) {
        return new TypedServerSentEvent<>(null, event, data, null, rawData);
    }

    /**
     * Checks if this event represents the standard LLM stream termination marker ({@code [DONE]}).
     *
     * @return {@code true} if {@link #rawData()} or {@link #data()} equals {@code "[DONE]"}, {@code false} otherwise
     */
    @Override
    public boolean isDone() {
        return "[DONE]".equals(rawData) || "[DONE]".equals(data);
    }

    /**
     * Converts this typed event into a raw string-based {@link ServerSentEvent}.
     *
     * @return an equivalent untyped {@link ServerSentEvent}
     */
    public ServerSentEvent toRaw() {
        return new ServerSentEvent(id, event, rawData, retry);
    }

    /**
     * Maps the deserialized data payload using the given mapper function.
     *
     * @param <R> the new payload type
     * @param mapper the transformation function
     * @return a new {@link TypedServerSentEvent} with mapped data
     */
    public <R> TypedServerSentEvent<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        R mappedData = (data != null) ? mapper.apply(data) : null;
        return new TypedServerSentEvent<>(id, event, mappedData, retry, rawData);
    }

    /**
     * Parses a lazy stream of raw text lines into a lazy stream of {@link TypedServerSentEvent}s.
     *
     * @param <T> the target payload type
     * @param lines the input line stream
     * @param codec the codec for payload deserialization
     * @param targetClass the target payload class
     * @return a lazy stream of typed events
     */
    public static <T> Stream<TypedServerSentEvent<T>> parse(
            Stream<String> lines,
            PayloadCodec codec,
            Class<T> targetClass) {
        return parse(lines, codec, (Type) targetClass);
    }

    /**
     * Parses a lazy stream of raw text lines into a lazy stream of {@link TypedServerSentEvent}s.
     *
     * @param <T> the target payload type
     * @param lines the input line stream
     * @param codec the codec for payload deserialization
     * @param targetType the target payload type
     * @return a lazy stream of typed events
     */
    public static <T> Stream<TypedServerSentEvent<T>> parse(
            Stream<String> lines,
            PayloadCodec codec,
            Type targetType) {
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(codec, "codec must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        return ServerSentEvent.parse(lines).map(raw -> raw.<T>toTyped(targetType, codec));
    }

    /**
     * Wraps a typed SSE connection factory into a lazy stream that reconnects per
     * {@link ReconnectPolicy}.
     *
     * @param <T> the payload type
     * @param opener the connection factory receiving the nullable {@code Last-Event-ID}
     * @param policy the reconnection policy
     * @return a lazy concatenation of the typed events of successive connections
     * @throws NullPointerException if {@code opener} or {@code policy} is {@code null}
     */
    public static <T> Stream<TypedServerSentEvent<T>> reconnecting(
            Function<String, Stream<TypedServerSentEvent<T>>> opener,
            ReconnectPolicy policy) {
        Objects.requireNonNull(opener, "opener must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        return ServerSentEventReconnector.reconnecting(opener, policy);
    }
}
