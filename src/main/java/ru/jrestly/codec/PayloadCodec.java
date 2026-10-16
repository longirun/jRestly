package ru.jrestly.codec;

import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Binds a wire payload to and from Java values for a single family of media
 * types (ADR-0060). This is the media-type-aware generalization of
 * {@link ru.jrestly.json.JsonCodec}: a client may register any number of
 * codecs — JSON is just the built-in default, provided by
 * {@link JsonCodecAdapter}.
 *
 * <p>Unlike {@code JsonCodec}, a {@code PayloadCodec} declares which media
 * types it handles via {@link #supports(String)} and receives the effective
 * media type on every call, so a client can pick the right format per
 * request/response instead of hard-coding JSON.
 *
 * <p>Contract every implementation must meet:
 * <ul>
 *   <li>{@link #supports(String)} is given a <em>normalized</em> media type:
 *       the type/subtype base without parameters, trimmed and lower-cased
 *       (for example {@code application/xml} from
 *       {@code Application/XML; charset=UTF-8}). A codec that handles a
 *       structured suffix of its base format (RFC 6839) must accept it too —
 *       {@link JsonCodecAdapter}, for instance, claims {@code application/json},
 *       {@code text/json} and every {@code *+json};</li>
 *   <li>{@link #serialize(Object, String) serialize} and
 *       {@link #deserialize(byte[], Type, String) deserialize} are synchronous
 *       and fail with an <em>unchecked</em> exception wrapping the underlying
 *       failure in the adapter's own exception family;</li>
 *   <li>the byte-array contract keeps the zero-copy pipeline intact
 *       (ADR-0054); {@code String} conveniences are only {@code default}
 *       methods;</li>
 *   <li>a {@code null} or empty payload deserializes to {@code null};</li>
 *   <li>instances are thread-safe — a client stores one instance and shares
 *       it across all calls.</li>
 * </ul>
 */
public interface PayloadCodec {

    /**
     * Whether this codec can bind payloads of the given media type.
     *
     * @param mediaType the normalized media type (base without parameters,
     *                  lower-cased); never {@code null}
     * @return {@code true} if this codec handles the media type
     */
    boolean supports(String mediaType);

    /**
     * Deserializes the given payload into a value of the given type.
     *
     * @param payload   the raw payload; may be {@code null} or empty
     * @param type      the target type, including generic signatures; never
     *                  {@code null}
     * @param mediaType the media type the payload was received as, or
     *                  {@code null} if unknown
     * @param <T>       the static type of the deserialized value
     * @return the deserialized value, or {@code null} for an empty payload
     * @throws RuntimeException if the payload cannot be bound (unchecked)
     */
    <T> T deserialize(byte[] payload, Type type, String mediaType);

    /**
     * Serializes the given value to the wire format this codec handles.
     *
     * @param value     the value to write; may be {@code null}
     * @param mediaType the media type the payload will be sent as, or
     *                  {@code null} if unknown
     * @return the encoded payload bytes
     * @throws RuntimeException if the value cannot be written (unchecked)
     */
    byte[] serialize(Object value, String mediaType);

    /**
     * Best-effort re-print of a payload for logging. Returns the payload
     * decoded as text unchanged when no reformatting is supported.
     *
     * @param payload   the raw payload; may be {@code null} or empty
     * @param mediaType the media type of the payload, or {@code null}
     * @param charset   the charset to decode with, or {@code null} for UTF-8
     * @return the (possibly re-printed) text, or {@code null} for a {@code null} payload
     */
    default String prettyPrint(byte[] payload, String mediaType, Charset charset) {
        if (payload == null) {
            return null;
        }
        return new String(payload, charset != null ? charset : StandardCharsets.UTF_8);
    }
}
