package ru.jrestly.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Objects;

/**
 * {@link JsonCodec} backed by a Jackson {@link ObjectMapper}.
 *
 * <p>Instances are created either from an explicit mapper (used as is, never
 * reconfigured) or via the {@code defaults(...)} factories, which result in:
 * <ul>
 *   <li>compact JSON on the wire (no pretty printing),</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} disabled,</li>
 *   <li>java.time support when {@code jackson-datatype-jsr310} is on the
 *       classpath: {@link LocalDateTime} serializes in the format chosen via
 *       {@link LocalDateTimeFormat} (the default
 *       {@link LocalDateTimeFormat#SPACE} prints
 *       {@code yyyy-MM-dd HH:mm:ss}, a fractional-seconds tail
 *       {@code .SSSSSSSSS} is appended only when the value carries
 *       sub-second precision) and deserializes leniently in every mode —
 *       both the classic space separator and the ISO-8601 {@code T}
 *       separator are accepted, with or without fractional seconds
 *       (1–9 digits).</li>
 * </ul>
 *
 * <p>Targets Jackson 2.x (a Jackson 3 would need a new adapter revision,
 * ADR-0001); the pinned compile/test version is 2.18.2.
 *
 * <p>Instances are thread-safe: {@code ObjectMapper} is thread-safe once
 * configured, and a codec never reconfigures its mapper.
 */
public class JacksonCodec implements JsonCodec {

    /**
     * {@code LocalDateTime} serialization ("wire") formats available from the
     * {@code defaults(...)} factories. Deserialization is lenient in every
     * mode and is unaffected by the choice: both the space separator and the
     * ISO-8601 {@code T} separator parse, with or without fractional seconds.
     */
    public enum LocalDateTimeFormat {

        /**
         * The historical jRestly wire format: {@code yyyy-MM-dd HH:mm:ss}
         * with an optional fractional-seconds tail (printed only when the
         * value carries sub-second precision). The default of
         * {@link #defaults()}; second-precision values serialize
         * byte-identically to the pre-0.6.1 output.
         */
        SPACE,

        /**
         * ISO-8601 with the {@code T} date-time separator:
         * {@code yyyy-MM-dd'T'HH:mm:ss} with the same optional
         * fractional-seconds tail as {@link #SPACE}.
         */
        ISO_8601
    }

    private final ObjectMapper mapper;

    public JacksonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * A default codec whose {@link LocalDateTime} values use the classic
     * {@link LocalDateTimeFormat#SPACE} wire format (byte-compatible with the
     * historical output).
     */
    public static JacksonCodec defaults() {
        return defaults(LocalDateTimeFormat.SPACE);
    }

    /**
     * A default codec with the given {@code LocalDateTime} wire format.
     * All other settings match {@link #defaults()}; deserialization stays
     * lenient in every mode.
     *
     * @param localDateTimeFormat the serialization format for
     *        {@link LocalDateTime}; never {@code null}
     * @throws NullPointerException if the format is {@code null}
     */
    public static JacksonCodec defaults(LocalDateTimeFormat localDateTimeFormat) {
        Objects.requireNonNull(localDateTimeFormat, "localDateTimeFormat");
        return new JacksonCodec(createDefault(localDateTimeFormat));
    }

    @Override
    public <T> T deserialize(String payload, Type type) {
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        try {
            return mapper.readValue(payload, javaType);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize JSON into " + type.getTypeName(), e);
        }
    }

    @Override
    public String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize " + value.getClass().getName() + " to JSON", e);
        }
    }

    @Override
    public String prettyPrint(String payload) {
        try {
            Object value = mapper.readValue(payload, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            return payload;
        }
    }

    private static ObjectMapper createDefault(LocalDateTimeFormat localDateTimeFormat) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // java.time support is optional: only jackson-databind is required for autodetection.
        // jsr310 types live in a nested class so that the JVM verifier does not resolve them
        // while loading JacksonCodec itself (NoClassDefFoundError on a jsr310-free classpath)
        if (isOnClasspath("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule")) {
            JavaTimeSupport.register(mapper, localDateTimeFormat);
        }

        return mapper;
    }

    private static boolean isOnClasspath(String fqn) {
        try {
            Class.forName(fqn, false, JacksonCodec.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static final class JavaTimeSupport {

        private JavaTimeSupport() {
        }

        /**
         * Wire format for printing in {@link LocalDateTimeFormat#SPACE} mode:
         * {@code yyyy-MM-dd HH:mm:ss} with an optional fractional-seconds
         * tail. The tail is omitted entirely when the value has second
         * precision, so such values serialize byte-identically to the
         * historical fixed pattern.
         */
        static final DateTimeFormatter SPACE_WIRE = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd HH:mm:ss")
                .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
                .toFormatter();

        /**
         * Wire format for printing in {@link LocalDateTimeFormat#ISO_8601}
         * mode: ISO-8601 with the {@code T} date-time separator,
         * {@code yyyy-MM-dd'T'HH:mm:ss}, with the same optional
         * fractional-seconds tail as {@link #SPACE_WIRE}.
         */
        static final DateTimeFormatter ISO_WIRE = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
                .toFormatter();

        /**
         * Parsing format: the date, the ISO-8601 {@code T} or the classic
         * space separator (each independently optional, so the {@code "T "}
         * combination is tolerated too), the time, and an optional
         * fractional-seconds tail of 1–9 digits.
         */
        static final DateTimeFormatter LENIENT = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd")
                .optionalStart().appendLiteral('T').optionalEnd()
                .optionalStart().appendLiteral(' ').optionalEnd()
                .appendPattern("HH:mm:ss")
                .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
                .toFormatter();

        static void register(ObjectMapper mapper, LocalDateTimeFormat format) {
            DateTimeFormatter wire = switch (format) {
                case SPACE -> SPACE_WIRE;
                case ISO_8601 -> ISO_WIRE;
            };
            JavaTimeModule module = new JavaTimeModule();
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(wire));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(LENIENT));
            mapper.registerModule(module);
        }
    }
}
