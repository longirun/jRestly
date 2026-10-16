package ru.jrestly.codec;

import ru.jrestly.json.JsonCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An ordered, immutable set of {@link PayloadCodec}s with a default fallback
 * (ADR-0060). A client uses it to pick the codec for a request or response
 * media type: {@link #resolve(String)} returns the first codec that claims the
 * type; {@link #resolveOrDefault(String)} additionally falls back to the
 * configured default (JSON, for strict backward compatibility).
 *
 * <p>Registration order is priority: when two codecs claim the same media
 * type, the one added first wins.
 */
public final class CodecRegistry {

    private final List<PayloadCodec> codecs;
    private final PayloadCodec defaultCodec;

    private CodecRegistry(List<PayloadCodec> codecs, PayloadCodec defaultCodec) {
        this.codecs = List.copyOf(codecs);
        this.defaultCodec = defaultCodec;
    }

    /**
     * A registry whose only codec is a {@link JsonCodecAdapter} wrapping the
     * given JSON codec — it both claims the JSON media types and serves as the
     * default for unknown ones.
     *
     * @param jsonCodec the JSON codec to adapt
     * @return a JSON-only registry
     */
    public static CodecRegistry of(JsonCodec jsonCodec) {
        return builder().defaultCodec(new JsonCodecAdapter(jsonCodec)).build();
    }

    /**
     * A builder for a registry.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The first registered codec that {@link PayloadCodec#supports(String)} the
     * given media type, or {@code null} when none does.
     *
     * @param mediaType the media type, possibly with parameters; may be {@code null}
     * @return the matching codec, or {@code null}
     */
    public PayloadCodec resolve(String mediaType) {
        String normalized = normalize(mediaType);
        if (normalized == null) {
            return null;
        }
        for (int i = 0; i < codecs.size(); i++) {
            PayloadCodec codec = codecs.get(i);
            if (codec.supports(normalized)) {
                return codec;
            }
        }
        return null;
    }

    /**
     * Like {@link #resolve(String)}, but returns {@link #defaultCodec()} when no
     * registered codec claims the media type.
     *
     * @param mediaType the media type, possibly with parameters; may be {@code null}
     * @return the matching codec, or the default codec if none matches
     */
    public PayloadCodec resolveOrDefault(String mediaType) {
        PayloadCodec codec = resolve(mediaType);
        return codec != null ? codec : defaultCodec;
    }

    /**
     * The default codec, or {@code null} if none was configured.
     *
     * @return the default codec
     */
    public PayloadCodec defaultCodec() {
        return defaultCodec;
    }

    /**
     * The registered codecs in registration order.
     *
     * @return an immutable list of codecs
     */
    public List<PayloadCodec> codecs() {
        return codecs;
    }

    /**
     * Normalizes a media type to its base form: parameters stripped, trimmed,
     * lower-cased. Returns {@code null} for a {@code null} or blank input.
     *
     * @param mediaType the raw media type
     * @return the normalized media type, or {@code null}
     */
    static String normalize(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        String trimmed = mediaType.trim();
        int semi = trimmed.indexOf(';');
        if (semi >= 0) {
            trimmed = trimmed.substring(0, semi).trim();
        }
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Builder for a {@link CodecRegistry}.
     */
    public static final class Builder {
        private final List<PayloadCodec> codecs = new ArrayList<>();
        private PayloadCodec defaultCodec;

        private Builder() {
        }

        /**
         * Registers a codec. Registration order is resolution priority.
         *
         * @param codec the codec to add; ignored when {@code null}
         * @return this builder, for chaining
         */
        public Builder codec(PayloadCodec codec) {
            if (codec != null) {
                codecs.add(codec);
            }
            return this;
        }

        /**
         * Sets the fallback codec for media types no registered codec claims.
         *
         * @param defaultCodec the default codec
         * @return this builder, for chaining
         */
        public Builder defaultCodec(PayloadCodec defaultCodec) {
            this.defaultCodec = defaultCodec;
            return this;
        }

        /**
         * Builds the registry. The default codec, if any, is appended to the
         * resolution order so its own {@code supports} is consulted as well.
         *
         * @return the immutable registry
         */
        public CodecRegistry build() {
            List<PayloadCodec> ordered = new ArrayList<>(codecs);
            if (defaultCodec != null && !ordered.contains(defaultCodec)) {
                ordered.add(defaultCodec);
            }
            return new CodecRegistry(ordered, defaultCodec);
        }
    }
}
