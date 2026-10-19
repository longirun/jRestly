package ru.jrestly.codec.protobuf;

import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.codec.PayloadCodecProvider;

/**
 * Service provider interface (SPI) registration that makes {@link ProtobufCodec}
 * light up automatically when {@code jrestly-codec-protobuf} is on the classpath
 * (ADR-0074).
 *
 * <p>With this provider a client needs no explicit
 * {@code JRestlyClient.builder().codec(...)} call: the Protocol Buffers codec
 * claims {@code application/x-protobuf} / {@code application/protobuf} /
 * {@code *+protobuf} by itself, while JSON remains the default. Users who want
 * the core's fail-fast behavior can pass an explicit {@code jsonCodec(...)}-only
 * registry.
 */
public final class ProtobufCodecProvider implements PayloadCodecProvider {

    /** The key class of the protobuf full runtime: its presence on the classpath is the availability probe. */
    private static final String PROTOBUF_MESSAGE_CLASS = "com.google.protobuf.Message";

    /** Priority of the official Protocol Buffers adapter (ADR-0074): ties are broken by registration order. */
    public static final int PRIORITY = 50;

    /**
     * Creates the provider instance; {@link java.util.ServiceLoader}
     * instantiates it through the {@code META-INF/services} registration.
     */
    public ProtobufCodecProvider() {
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(PROTOBUF_MESSAGE_CLASS, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public PayloadCodec createCodec() {
        return new ProtobufCodec();
    }
}
