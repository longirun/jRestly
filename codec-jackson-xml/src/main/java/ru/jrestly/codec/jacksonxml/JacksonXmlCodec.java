package ru.jrestly.codec.jacksonxml;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import ru.jrestly.codec.PayloadCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@link PayloadCodec} backed by a Jackson {@link XmlMapper} (ADR-0060) — the
 * official XML adapter for jRestly, kept out of the zero-dependency core.
 *
 * <p>Claims the XML media type family: {@code application/xml}, {@code text/xml}
 * and every structured suffix ending in {@code +xml} (RFC 6839, for example
 * {@code application/vnd.company.v1+xml}). Register it on a client to make
 * {@code @ContentType(MediaType.APPLICATION_XML)} / {@code @Accept(...)} bind
 * request and response DTOs as XML:
 *
 * <pre>{@code
 * JRestlyClient client = JRestlyClient.builder()
 *         .baseUrl(url)
 *         .codec(JacksonXmlCodec.defaults())
 *         .build();
 * }</pre>
 *
 * <p>Without this codec an XML {@code Content-Type} with a DTO body fails fast
 * (see {@link ru.jrestly.codec.CodecRegistry}); the codec is opt-in so the core
 * stays dependency-free.
 *
 * <p>Instances are created either from an explicit mapper (used as is, never
 * reconfigured) or via {@link #defaults()}, which disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} and registers the {@code java.time} module
 * when {@code jackson-datatype-jsr310} is on the classpath.
 *
 * <p>Thread-safe: the underlying {@code XmlMapper} is thread-safe once
 * configured, and a codec never reconfigures its mapper.
 */
public final class JacksonXmlCodec implements PayloadCodec {

    private final XmlMapper mapper;

    /**
     * Creates a codec backed by the given mapper.
     *
     * <p>The mapper is used as is and never reconfigured — configure it before
     * handing it in.
     *
     * @param mapper the {@link XmlMapper} backing this codec; never {@code null}
     */
    public JacksonXmlCodec(XmlMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * A codec with sensible defaults: unknown properties are ignored and the
     * {@code java.time} module is registered when available.
     *
     * @return a new codec with default settings
     */
    public static JacksonXmlCodec defaults() {
        return new JacksonXmlCodec(createDefault());
    }

    /**
     * The wrapped mapper.
     *
     * @return the {@link XmlMapper}
     */
    public XmlMapper mapper() {
        return mapper;
    }

    @Override
    public boolean supports(String mediaType) {
        return "application/xml".equals(mediaType)
                || "text/xml".equals(mediaType)
                || mediaType.endsWith("+xml");
    }

    @Override
    public <T> T deserialize(byte[] payload, Type type, String mediaType) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        try {
            return mapper.readValue(payload, javaType);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize XML into " + type.getTypeName(), e);
        }
    }

    @Override
    public byte[] serialize(Object value, String mediaType) {
        if (value == null) {
            return new byte[0];
        }
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize " + value.getClass().getName() + " to XML", e);
        }
    }

    @Override
    public String prettyPrint(byte[] payload, String mediaType, Charset charset) {
        if (payload == null) {
            return null;
        }
        if (payload.length == 0) {
            return "";
        }
        try {
            Object value = mapper.readValue(payload, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            return new String(payload, charset != null ? charset : StandardCharsets.UTF_8);
        }
    }

    private static XmlMapper createDefault() {
        XmlMapper mapper = new XmlMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator.Feature.WRITE_XML_DECLARATION, false);

        // java.time support is optional: only jackson-dataformat-xml/databind is required
        // for the mapper. Registering the module directly would tie the class to jsr310,
        // so the lookup is guarded and the module lives behind a nested holder.
        if (isOnClasspath("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule")) {
            JavaTimeSupport.register(mapper);
        }
        return mapper;
    }

    private static boolean isOnClasspath(String fqn) {
        try {
            Class.forName(fqn, false, JacksonXmlCodec.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Indirection so jsr310 types are only resolved when actually present. */
    private static final class JavaTimeSupport {

        private JavaTimeSupport() {
        }

        static void register(ObjectMapper mapper) {
            mapper.findAndRegisterModules();
        }
    }
}
