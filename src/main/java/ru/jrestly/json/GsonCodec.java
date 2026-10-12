package ru.jrestly.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;

import java.lang.reflect.Type;

/**
 * {@link JsonCodec} backed by a Gson {@link Gson}.
 *
 * <p>Instances are created either from an explicit {@code Gson} (used as is,
 * never reconfigured) or via {@link #defaults()}, which is a plain
 * {@code new GsonBuilder().create()} — Gson's own semantics with no
 * jRestly-specific reconfiguration. Consequences, which are documented
 * behavior rather than jRestly choices:
 * <ul>
 *   <li>{@code java.util.Date} values serialize as a locale-formatted string
 *       (Gson's default {@code DateFormat} style, second precision — the
 *       sub-second part of a {@code Date} is lost);</li>
 *   <li>{@code java.time} values use Gson's frozen reflective-style
 *       representation (e.g. {@code LocalDateTime} prints as
 *       {@code {"date":{"year":2026,"month":8,"day":24},
 *       "time":{"hour":12,"minute":34,"second":56,"nano":0}}}) — frozen by
 *       Gson 2.14 for compatibility with the reflective output of earlier
 *       versions, deliberately not ISO-8601. Sub-second precision lives in
 *       the integer {@code nano} field and is never truncated,</li>
 *   <li>unknown JSON properties are ignored on deserialization.</li>
 * </ul>
 *
 * <p>Requires Gson 2.8.3+ — the lazy pretty twin relies on
 * {@code Gson.newBuilder()}, which landed in 2.8.3; the pinned compile/test
 * version is 2.14.0. {@code java.time} values have no dedicated adapter
 * before 2.14 and fall through to Gson's general reflective path (on modern
 * JDKs that reflects into the JDK's private fields, so {@code --add-opens}
 * may be needed); 2.14+ ships a native adapter with a frozen wire format
 * instead.
 *
 * <p>{@code deserialize}/{@code serialize} fail with an unchecked
 * {@link JsonSyntaxException} naming the target, the original Gson
 * exception kept as the cause; {@code prettyPrint} is best-effort (the
 * payload is returned unchanged when it cannot be re-printed). Instances
 * are thread-safe: {@code Gson} is thread-safe and the lazy pretty twin is
 * cached in a volatile field.
 */
public class GsonCodec implements JsonCodec {

    private final Gson gson;
    private volatile Gson prettyTwin;

    public GsonCodec(Gson gson) {
        this.gson = gson;
    }

    /**
     * A default codec built on a plain {@code GsonBuilder().create()}:
     * compact JSON, Gson's native date handling, unknown properties ignored.
     */
    public static GsonCodec defaults() {
        return new GsonCodec(new GsonBuilder().create());
    }

    @Override
    public <T> T deserialize(String payload, Type type) {
        try {
            return gson.fromJson(payload, type);
        } catch (JsonParseException e) {
            throw new JsonSyntaxException("Failed to deserialize JSON into " + type.getTypeName(), e);
        }
    }

    @Override
    public String serialize(Object value) {
        try {
            return gson.toJson(value);
        } catch (JsonParseException e) {
            throw new JsonSyntaxException("Failed to serialize " + value.getClass().getName() + " to JSON", e);
        }
    }

    @Override
    public String prettyPrint(String payload) {
        try {
            Object value = gson.fromJson(payload, Object.class);
            return prettyTwin().toJson(value);
        } catch (RuntimeException e) {
            return payload;
        }
    }

    /**
     * Gson's pretty-printing flag is per-instance, so a pretty twin is needed
     * instead of a per-call flag. It is created lazily on first use and
     * cached: a codec that never pretty-prints allocates nothing. The
     * instance is a copy of the underlying {@code Gson} configuration plus
     * pretty printing, so custom type adapters are honored when re-printing.
     */
    private Gson prettyTwin() {
        Gson twin = prettyTwin;
        if (twin == null) {
            twin = gson.newBuilder().setPrettyPrinting().create();
            prettyTwin = twin;
        }
        return twin;
    }
}
