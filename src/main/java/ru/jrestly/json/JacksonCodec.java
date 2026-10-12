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

/**
 * {@link JsonCodec} backed by a Jackson {@link ObjectMapper}.
 *
 * <p>Instances are created either from an explicit mapper (used as is, never
 * reconfigured) or via {@link #defaults()}, which configures:
 * <ul>
 *   <li>compact JSON on the wire (no pretty printing),</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} disabled,</li>
 *   <li>java.time support when {@code jackson-datatype-jsr310} is on the
 *       classpath: {@link LocalDateTime} serializes as
 *       {@code yyyy-MM-dd HH:mm:ss} (a fractional-seconds tail
 *       {@code .SSSSSSSSS} is appended only when the value carries
 *       sub-second precision) and deserializes leniently — both the classic
 *       space separator and the ISO-8601 {@code T} separator are accepted,
 *       with or without fractional seconds (1–9 digits).</li>
 * </ul>
 */
public class JacksonCodec implements JsonCodec {

    private final ObjectMapper mapper;

    public JacksonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static JacksonCodec defaults() {
        return new JacksonCodec(createDefault());
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

    private static ObjectMapper createDefault() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // java.time support is optional: only jackson-databind is required for autodetection.
        // jsr310 types live in a nested class so that the JVM verifier does not resolve them
        // while loading JacksonCodec itself (NoClassDefFoundError on a jsr310-free classpath)
        if (isOnClasspath("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule")) {
            JavaTimeSupport.register(mapper);
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
         * Wire format for printing: {@code yyyy-MM-dd HH:mm:ss} with an
         * optional fractional-seconds tail. The tail is omitted entirely when
         * the value has second precision, so such values serialize
         * byte-identically to the historical fixed pattern.
         */
        static final DateTimeFormatter WIRE = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd HH:mm:ss")
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

        static void register(ObjectMapper mapper) {
            JavaTimeModule module = new JavaTimeModule();
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(WIRE));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(LENIENT));
            mapper.registerModule(module);
        }
    }
}
