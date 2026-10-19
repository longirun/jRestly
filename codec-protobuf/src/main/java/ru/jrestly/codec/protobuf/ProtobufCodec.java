package ru.jrestly.codec.protobuf;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import ru.jrestly.codec.PayloadCodec;

import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PayloadCodec} backed by the {@code protobuf-java} runtime (ADR-0074) —
 * the official Protocol Buffers adapter for jRestly, kept out of the
 * zero-dependency core.
 *
 * <p>Claims the Protocol Buffers media type family: {@code application/x-protobuf}
 * (the de-facto legacy type) and {@code application/protobuf} (the IANA-registered
 * type), plus every structured suffix ending in {@code +protobuf} (RFC 6839, for
 * example {@code application/vnd.company.v1+protobuf}). Register it on a client to
 * make {@code @ContentType} / {@code @Accept} declarations bind request and
 * response DTOs that are protoc-generated {@code Message} types:
 *
 * <pre>{@code
 * JRestlyClient client = JRestlyClient.builder()
 *         .baseUrl(url)
 *         .codec(new ProtobufCodec())
 *         .build();
 * }</pre>
 *
 * <p>Unlike the JSON/XML/YAML codecs, protobuf is <em>schema-bound</em>: the wire
 * type is a generated message class with its own {@code Parser}, so
 * {@link #deserialize(byte[], Type, String)} resolves the parser for the target
 * class (the protoc-generated {@code PARSER} field, or a static
 * {@code getDefaultInstance()} + {@code getParserForType()} for hand-written
 * messages) and caches it; values that are not messages fail fast.
 *
 * <p>Without this codec a protobuf {@code Content-Type} with a DTO body fails fast
 * (see {@link ru.jrestly.codec.CodecRegistry}); the codec is opt-in so the core
 * stays dependency-free.
 *
 * <p>Thread-safe: {@code Parser} instances are thread-safe, and the parser cache
 * is a {@link ConcurrentHashMap} — one lookup per message class for the lifetime
 * of the codec.
 */
public final class ProtobufCodec implements PayloadCodec {

    /** The protoc invariant: every generated message class exposes this static field. */
    private static final String PARSER_FIELD = "PARSER";

    /** The conventional static factory of hand-written messages. */
    private static final String DEFAULT_INSTANCE_METHOD = "getDefaultInstance";

    /** Hex rendering line width for {@link #prettyPrint(byte[], String, Charset)}. */
    private static final int HEX_LINE_WIDTH = 16;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /** Parsers resolved so far, one per message class. */
    private final Map<Class<?>, Parser<?>> parsers = new ConcurrentHashMap<>();

    /**
     * Creates a new codec. There is no per-codec configuration in Phase 1:
     * resolution and caching are fixed, see the class-level documentation.
     */
    public ProtobufCodec() {
    }

    /**
     * A codec with the standard settings (Phase 1 has none beyond resolution
     * and caching, so this is a convenience factory mirroring the sibling
     * codecs).
     *
     * @return a new codec
     */
    public static ProtobufCodec defaults() {
        return new ProtobufCodec();
    }

    @Override
    public boolean supports(String mediaType) {
        return "application/x-protobuf".equals(mediaType)
                || "application/protobuf".equals(mediaType)
                || mediaType.endsWith("+protobuf");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] payload, Type type, String mediaType) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        if (!(type instanceof Class<?> messageClass)) {
            throw new IllegalArgumentException(
                    "Protocol Buffers payloads bind to a generated message class, not " + type.getTypeName());
        }
        if (!Message.class.isAssignableFrom(messageClass)) {
            throw new IllegalArgumentException(
                    "Protocol Buffers payloads bind to com.google.protobuf.Message types, not "
                            + messageClass.getName());
        }
        try {
            return (T) parserFor(messageClass).parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new UncheckedIOException(
                    "Failed to deserialize Protocol Buffers payload into " + messageClass.getName(), e);
        }
    }

    @Override
    public byte[] serialize(Object value, String mediaType) {
        if (value == null) {
            return new byte[0];
        }
        if (value instanceof MessageLite message) {
            return message.toByteArray();
        }
        throw new IllegalArgumentException(
                "Protocol Buffers payloads serialize com.google.protobuf.Message values, not "
                        + value.getClass().getName());
    }

    @Override
    public String prettyPrint(byte[] payload, String mediaType, Charset charset) {
        if (payload == null) {
            return null;
        }
        if (payload.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(payload.length * 5);
        for (int i = 0; i < payload.length; i++) {
            if (i > 0 && i % HEX_LINE_WIDTH == 0) {
                out.append('\n');
            } else if (i > 0) {
                out.append(' ');
            }
            out.append(HEX_DIGITS[(payload[i] >> 4) & 0x0F]);
            out.append(HEX_DIGITS[payload[i] & 0x0F]);
        }
        return out.toString();
    }

    /**
     * The parser for the given message class, resolving it on first use and
     * caching it afterwards.
     *
     * <p>Package-private: exercised directly by the cache test, not part of the
     * public surface.
     *
     * @param messageClass the generated (or hand-written) message class
     * @return the cached {@link Parser}
     * @throws IllegalArgumentException if no parser can be resolved for the class
     */
    Parser<?> parserFor(Class<?> messageClass) {
        return parsers.computeIfAbsent(messageClass, this::resolve);
    }

    private Parser<?> resolve(Class<?> messageClass) {
        Parser<?> parser = readParserField(messageClass);
        if (parser != null) {
            return parser;
        }
        for (Method method : messageClass.getMethods()) {
            if (method.getName().equals(DEFAULT_INSTANCE_METHOD)
                    && method.getParameterCount() == 0
                    && (method.getModifiers() & Modifier.STATIC) != 0) {
                try {
                    MessageLite instance = (MessageLite) method.invoke(null);
                    Parser<?> viaInstance = instance.getParserForType();
                    if (viaInstance != null) {
                        return viaInstance;
                    }
                } catch (ReflectiveOperationException | IllegalArgumentException | ClassCastException e) {
                    throw new IllegalStateException(
                            "Failed to resolve the parser for " + messageClass.getName(), e);
                }
            }
        }
        throw new IllegalArgumentException(
                "No parser found for " + messageClass.getName() + ": expected the protoc-generated "
                        + "PARSER field or a static getDefaultInstance() with getParserForType()");
    }

    /** The generated {@code PARSER} field, or {@code null} when absent. */
    private static Parser<?> readParserField(Class<?> messageClass) {
        try {
            Field field = messageClass.getField(PARSER_FIELD);
            if (Parser.class.isAssignableFrom(field.getType())) {
                return (Parser<?>) field.get(null);
            }
        } catch (NoSuchFieldException e) {
            // Not protoc-generated: fall through to the default-instance path.
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "The PARSER field of " + messageClass.getName() + " is not accessible", e);
        }
        return null;
    }
}
