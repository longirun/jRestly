package ru.jrestly.codec;

import ru.jrestly.json.JsonCodec;

import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Bridges the existing JSON-only {@link JsonCodec} into the media-type-aware
 * {@link PayloadCodec} SPI (ADR-0060), so current JSON users keep working
 * byte-for-byte while other formats become pluggable.
 *
 * <p>Claims {@code application/json}, {@code text/json} and every structured
 * suffix ending in {@code +json} (RFC 6839, for example
 * {@code application/vnd.company.v1+json}).
 */
public final class JsonCodecAdapter implements PayloadCodec {

    private final JsonCodec jsonCodec;

    /**
     * Wraps a JSON codec.
     *
     * @param jsonCodec the JSON codec to adapt; never {@code null}
     */
    public JsonCodecAdapter(JsonCodec jsonCodec) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    }

    /**
     * The wrapped JSON codec.
     *
     * @return the JSON codec
     */
    public JsonCodec jsonCodec() {
        return jsonCodec;
    }

    @Override
    public boolean supports(String mediaType) {
        return "application/json".equals(mediaType)
                || "text/json".equals(mediaType)
                || mediaType.endsWith("+json");
    }

    @Override
    public <T> T deserialize(byte[] payload, Type type, String mediaType) {
        return jsonCodec.deserializeBytes(payload, type);
    }

    @Override
    public byte[] serialize(Object value, String mediaType) {
        return jsonCodec.serializeToBytes(value);
    }

    @Override
    public String prettyPrint(byte[] payload, String mediaType, Charset charset) {
        if (payload == null) {
            return null;
        }
        if (payload.length == 0) {
            return "";
        }
        Charset effective = charset != null ? charset : StandardCharsets.UTF_8;
        return jsonCodec.prettyPrint(new String(payload, effective));
    }
}
