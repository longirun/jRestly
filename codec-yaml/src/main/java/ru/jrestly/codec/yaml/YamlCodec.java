package ru.jrestly.codec.yaml;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import ru.jrestly.codec.PayloadCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@link PayloadCodec} backed by a Jackson {@link YAMLMapper} (ADR-0073) — the
 * official YAML adapter for jRestly, kept out of the zero-dependency core.
 *
 * <p>Claims the YAML media type family: {@code application/yaml} and
 * {@code text/yaml} (RFC 9512) and every structured suffix ending in
 * {@code +yaml} (RFC 6839, for example {@code application/vnd.company.v1+yaml}).
 * Register it on a client to make {@code @ContentType(MediaType.APPLICATION_YAML)}
 * / {@code @Accept(...)} bind request and response DTOs as YAML:
 *
 * <pre>{@code
 * JRestlyClient client = JRestlyClient.builder()
 *         .baseUrl(url)
 *         .codec(YamlCodec.defaults())
 *         .build();
 * }</pre>
 *
 * <p>Without this codec a YAML {@code Content-Type} with a DTO body fails fast
 * (see {@link ru.jrestly.codec.CodecRegistry}); the codec is opt-in so the core
 * stays dependency-free.
 *
 * <p>Instances are created either from an explicit mapper (used as is, never
 * reconfigured) or via {@link #defaults()}, which disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} and registers the {@code java.time} module
 * when {@code jackson-datatype-jsr310} is on the classpath.
 *
 * <p>Thread-safe: the underlying {@code YAMLMapper} is thread-safe once
 * configured, and a codec never reconfigures its mapper.
 */
public final class YamlCodec implements PayloadCodec {

    private final YAMLMapper mapper;

    /**
     * Creates a codec backed by the given mapper.
     *
     * <p>The mapper is used as is and never reconfigured — configure it before
     * handing it in.
     *
     * @param mapper the {@link YAMLMapper} backing this codec; never {@code null}
     */
    public YamlCodec(YAMLMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * A codec with sensible defaults: unknown properties are ignored and the
     * {@code java.time} module is registered when available.
     *
     * @return a new codec with default settings
     */
    public static YamlCodec defaults() {
        return new YamlCodec(createDefault());
    }

    /**
     * The wrapped mapper.
     *
     * @return the {@link YAMLMapper}
     */
    public YAMLMapper mapper() {
        return mapper;
    }

    @Override
    public boolean supports(String mediaType) {
        return "application/yaml".equals(mediaType)
                || "text/yaml".equals(mediaType)
                || mediaType.endsWith("+yaml");
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
            throw new UncheckedIOException("Failed to deserialize YAML into " + type.getTypeName(), e);
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
            throw new UncheckedIOException("Failed to serialize " + value.getClass().getName() + " to YAML", e);
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

    private static YAMLMapper createDefault() {
        YAMLMapper mapper = new YAMLMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // java.time support is optional: only jackson-dataformat-yaml/databind is required
        // for the mapper. Registering the module directly would tie the class to jsr310,
        // so the lookup is guarded and the module lives behind a nested holder.
        if (isOnClasspath("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule")) {
            JavaTimeSupport.register(mapper);
        }
        return mapper;
    }

    private static boolean isOnClasspath(String fqn) {
        try {
            Class.forName(fqn, false, YamlCodec.class.getClassLoader());
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
