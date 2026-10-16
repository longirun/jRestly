package ru.jrestly.codec;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SPI provider registered for the test classpath
 * ({@code META-INF/services/ru.jrestly.codec.PayloadCodecProvider}) to guard
 * ServiceLoader codec auto-detection end-to-end (ADR-0060, phase 3).
 *
 * <p>Unavailable by default so that it never interferes with other tests;
 * a test flips {@link #AVAILABLE} for the duration of its scenario.
 */
public final class TestSpiPayloadCodecProvider implements PayloadCodecProvider {

    /** Media type claimed by the auto-discovered test codec. */
    public static final String MEDIA = "text/x-jrestly-spi";

    /** Whether the provider reports itself available; {@code false} outside explicit scenarios. */
    public static final AtomicBoolean AVAILABLE = new AtomicBoolean(false);

    /** Number of codecs created, for auto-detection evidence in assertions. */
    public static final AtomicInteger CREATE_COUNT = new AtomicInteger();

    /** ServiceLoader requires a public no-argument constructor. */
    public TestSpiPayloadCodecProvider() {
    }

    @Override
    public boolean isAvailable() {
        return AVAILABLE.get();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public PayloadCodec createCodec() {
        CREATE_COUNT.incrementAndGet();
        return new Codec();
    }

    /** Marker codec serializing values as {@code "spi:" + value}. */
    public static final class Codec implements PayloadCodec {
        @Override
        public boolean supports(String mediaType) {
            return MEDIA.equals(mediaType);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(byte[] payload, Type type, String mediaType) {
            if (payload == null || payload.length == 0) {
                return null;
            }
            String text = new String(payload, StandardCharsets.UTF_8);
            if (type == String.class) {
                return (T) text;
            }
            throw new IllegalStateException("Test codec cannot deserialize " + type);
        }

        @Override
        public byte[] serialize(Object value, String mediaType) {
            return ("spi:" + value).getBytes(StandardCharsets.UTF_8);
        }
    }
}
