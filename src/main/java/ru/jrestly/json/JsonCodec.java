package ru.jrestly.json;

import java.lang.reflect.Type;

/**
 * Binds JSON payloads to and from Java values for a client.
 *
 * <p>This is the library's codec SPI: a client stores exactly one instance
 * and shares it across all of its calls. Implementations back a client on
 * one of the supported JSON libraries (Jackson, Gson, or Moshi via the
 * bundled adapters, see {@link JsonCodecResolver}) or on a user-supplied
 * codec of any other library.
 *
 * <p>Contract every implementation must meet:
 * <ul>
 *   <li>{@link #serialize(Object) serialize} and
 *       {@link #deserialize(String, Type) deserialize} are synchronous and
 *       fail with an <em>unchecked</em> exception wrapping the underlying
 *       library failure in its own exception family — an
 *       {@code UncheckedIOException}-style contract: the wrapper type is
 *       chosen by the adapter (Jackson's {@code UncheckedIOException},
 *       Gson's {@code JsonSyntaxException}, Moshi's {@code
 *       JsonDataException}) and is never a checked type. The message names
 *       the value's type; the original exception is kept as the cause;</li>
 *   <li>{@code deserialize} honors {@link java.lang.reflect.Type}: raw
 *       classes as well as parameterized and array types, so generic
 *       containers deserialize with their full element types;</li>
 *   <li>top-level {@code null} serializes to the JSON literal
 *       {@code null} in every bundled adapter, and a JSON {@code null}
 *       payload deserializes to {@code null};</li>
 *   <li>{@link #prettyPrint(String) prettyPrint} is best-effort: it may
 *       return the payload unchanged when it cannot re-print it;</li>
 *   <li>instances are thread-safe — the sharing above is a promise the
 *       library relies on, including for user-supplied codecs.</li>
 * </ul>
 */
public interface JsonCodec {

    /**
     * Deserializes the given JSON payload into a value of the given type.
     *
     * @param payload the JSON document to read
     * @param type    the target type, including generic signatures; never
     *                {@code null}
     * @param <T>     the static type of the deserialized value
     * @return the deserialized value, or {@code null} for a JSON
     *         {@code null} payload
     * @throws RuntimeException if the payload cannot be bound to the type
     *         (unchecked; the concrete wrapper type is adapter-specific,
     *         see the class-level contract)
     */
    <T> T deserialize(String payload, Type type);

    /**
     * Serializes the given value to a JSON document.
     *
     * @param value the value to write; may be {@code null} (bundled
     *              adapters serialize it to the JSON literal
     *              {@code null})
     * @return the JSON document
     * @throws RuntimeException if the value cannot be written
     *         (unchecked; the concrete wrapper type is adapter-specific,
     *         see the class-level contract)
     */
    String serialize(Object value);

    /**
     * Re-prints the payload with indentation, if possible.
     *
     * <p>Best-effort: returns {@code payload} unchanged when it cannot be
     * re-printed (for example, the payload is not valid JSON).
     *
     * @param payload the JSON document to re-print
     * @return the re-printed document, or {@code payload} unchanged
     */
    String prettyPrint(String payload);
}
