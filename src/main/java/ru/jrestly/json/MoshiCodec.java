package ru.jrestly.json;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import okio.Buffer;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * {@link JsonCodec} backed by a Moshi {@link Moshi}.
 *
 * <p>Instances are created either from an explicit {@code Moshi} (used as
 * is, never reconfigured) or via {@link #defaults()}, which is a plain
 * {@code new Moshi.Builder().build()} — Moshi's own semantics with no
 * jRestly-specific reconfiguration and no add-on registration.
 * Consequences, which are documented behavior rather than jRestly
 * choices:
 * <ul>
 *   <li>unknown JSON properties are ignored on deserialization (Moshi's
 *       native leniency);</li>
 *   <li>null object fields are omitted on serialization (a {@code
 *       JsonWriter} without {@code serializeNulls});</li>
 *   <li>{@code java.time} and {@code java.util.Date} values are not
 *       first-class: Moshi 1.x has no built-in or standard add-on adapters
 *       for them, and its reflective class adapter refuses {@code java.*}
 *       platform classes outright (a plain {@code Moshi} instance fails
 *       with a "Platform class ... requires explicit JsonAdapter to be
 *       registered" error, wrapped by this codec as described below). A
 *       host that needs date/time fields registers its own adapters on a
 *       {@code Moshi} instance (e.g. ISO-8601 string adapters) and passes
 *       it to {@link #MoshiCodec(Moshi)}.</li>
 * </ul>
 *
 * <p>Targets Moshi 1.x; the pinned compile/test version is 1.15.2.
 *
 * <p>{@code deserialize}/{@code serialize} fail with an unchecked
 * {@link JsonDataException} (Moshi's data-exception family) naming the
 * target, the original exception kept as the cause;
 * {@code prettyPrint} is best-effort (the payload is returned unchanged
 * when it cannot be re-printed). Instances are thread-safe: {@code Moshi}
 * is thread-safe and all scratch buffers are per-call.
 */
public class MoshiCodec implements JsonCodec {

    private final Moshi moshi;

    public MoshiCodec(Moshi moshi) {
        this.moshi = moshi;
    }

    /**
     * A default codec built on a plain {@code Moshi.Builder().build()}:
     * compact JSON, Moshi's native leniency and null handling, no add-on
     * adapters.
     */
    public static MoshiCodec defaults() {
        return new MoshiCodec(new Moshi.Builder().build());
    }

    @Override
    public <T> T deserialize(String payload, Type type) {
        try {
            Buffer source = new Buffer();
            source.writeUtf8(payload);
            JsonReader reader = JsonReader.of(source);
            JsonAdapter<T> adapter = moshi.adapter(type);
            return adapter.fromJson(reader);
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers Moshi's adapter-lookup failures (e.g. a
            // "platform class" such as java.time.LocalDateTime with no adapter
            // registered: an IllegalArgumentException, not a JsonDataException)
            // as well as its data-exception family
            throw new JsonDataException("Failed to deserialize JSON into " + type.getTypeName(), e);
        }
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return "null";
        }
        Buffer sink = new Buffer();
        try {
            JsonAdapter<Object> adapter = moshi.adapter((Type) value.getClass());
            adapter.toJson(JsonWriter.of(sink), value);
        } catch (IOException | RuntimeException e) {
            // see deserialize(): RuntimeException covers adapter-lookup failures
            // as well as Moshi's data-exception family
            throw new JsonDataException("Failed to serialize " + value.getClass().getName() + " to JSON", e);
        }
        return sink.readUtf8();
    }

    @Override
    public String prettyPrint(String payload) {
        Buffer source = new Buffer();
        source.writeUtf8(payload);
        Buffer sink = new Buffer();
        try (JsonReader reader = JsonReader.of(source);
             JsonWriter writer = indentedWriter(sink)) {
            copyValue(reader, writer);
        } catch (IOException | RuntimeException e) {
            return payload;
        }
        return sink.readUtf8();
    }

    private static JsonWriter indentedWriter(Buffer sink) {
        JsonWriter writer = JsonWriter.of(sink);
        writer.setIndent("  ");
        return writer;
    }

    /**
     * Re-prints the reader's value token for token into an indented writer.
     * Numbers pass through as their raw literal ({@link JsonReader#nextString()}
     * on a number token), so integer literals stay integers — a tree-based
     * re-print (via the {@code Object} adapter) would widen every number to
     * a double.
     */
    private static void copyValue(JsonReader reader, JsonWriter writer) throws IOException {
        switch (reader.peek()) {
            case BEGIN_ARRAY -> {
                reader.beginArray();
                writer.beginArray();
                while (reader.hasNext()) {
                    copyValue(reader, writer);
                }
                reader.endArray();
                writer.endArray();
            }
            case BEGIN_OBJECT -> {
                reader.beginObject();
                writer.beginObject();
                while (reader.hasNext()) {
                    writer.name(reader.nextName());
                    copyValue(reader, writer);
                }
                reader.endObject();
                writer.endObject();
            }
            case STRING -> writer.value(reader.nextString());
            case NUMBER -> {
                // value(String) would quote the literal; value(BufferedSource)
                // writes it raw, so number formatting survives the re-print
                Buffer literal = new Buffer();
                literal.writeUtf8(reader.nextString());
                writer.value(literal);
            }
            case BOOLEAN -> writer.value(reader.nextBoolean());
            case NULL -> writer.nullValue();
            default -> throw new JsonDataException("Unexpected token at path " + reader.getPath());
        }
    }
}
