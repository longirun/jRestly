package ru.jrestly.json;

import java.lang.reflect.Type;

/**
 * A fallback {@link JsonCodec} used when no supported JSON library (Jackson,
 * Gson, or Moshi) is present on the classpath (ADR-0045).
 *
 * <p>Allows {@link ru.jrestly.JRestlyClient} to operate in zero-dependency environments
 * when declarative endpoints deal exclusively with raw types ({@link String}, {@code byte[]},
 * {@link java.io.InputStream}, {@link java.util.stream.Stream}, {@link Void}) or
 * form-urlencoded and multipart payloads without JSON parts.
 *
 * <p>Throws {@link IllegalStateException} on attempts to serialize or deserialize
 * non-null payloads, directing the developer to include a supported JSON library or
 * configure an explicit codec.
 */
public final class NoOpJsonCodec implements JsonCodec {

    private static final NoOpJsonCodec INSTANCE = new NoOpJsonCodec();

    static final String ERROR_MESSAGE = "No JSON codec found on the classpath. "
            + "Add jackson-databind, gson, or moshi as a runtime dependency, "
            + "or set one explicitly via JRestlyClient.builder().jsonCodec(...)";

    /**
     * Returns the singleton instance of {@code NoOpJsonCodec}.
     *
     * @return the singleton instance
     */
    public static NoOpJsonCodec instance() {
        return INSTANCE;
    }

    /**
     * Package-private constructor for internal and test use.
     */
    NoOpJsonCodec() {
    }

    @Override
    public <T> T deserialize(String payload, Type type) {
        if (payload == null || payload.isBlank() || "null".equals(payload.trim())) {
            return null;
        }
        throw new IllegalStateException(ERROR_MESSAGE);
    }

    @Override
    public <T> T deserializeBytes(byte[] payload, Type type) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        String s = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        return deserialize(s, type);
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return "null";
        }
        throw new IllegalStateException(ERROR_MESSAGE);
    }

    @Override
    public byte[] serializeToBytes(Object value) {
        if (value == null) {
            return new byte[] {'n', 'u', 'l', 'l'};
        }
        throw new IllegalStateException(ERROR_MESSAGE);
    }

    @Override
    public String prettyPrint(String payload) {
        return payload;
    }
}
