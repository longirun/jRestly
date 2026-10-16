package ru.jrestly.codec.jacksonxml;

import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.codec.PayloadCodecProvider;

/**
 * Service provider interface (SPI) registration that makes
 * {@link JacksonXmlCodec} light up automatically when
 * {@code jrestly-codec-jackson-xml} is on the classpath (ADR-0060, phase 3).
 *
 * <p>With this provider a client needs no explicit
 * {@code JRestlyClient.builder().codec(...)} call: the XML codec claims
 * {@code application/xml} / {@code text/xml} / {@code *+xml} by itself, while
 * JSON remains the default. Users who want the core's fail-fast behavior can
 * pass an explicit {@code jsonCodec(...)}-only registry.
 */
public final class JacksonXmlCodecProvider implements PayloadCodecProvider {

    /** Jackson XML's key class: its presence on the classpath is the availability probe. */
    private static final String JACKSON_XML_CLASS = "com.fasterxml.jackson.dataformat.xml.XmlMapper";

    /** Priority of the official XML adapter (ADR-0060): ties are broken by registration order. */
    public static final int PRIORITY = 50;

    /**
     * Creates the provider instance; {@link java.util.ServiceLoader}
     * instantiates it through the {@code META-INF/services} registration.
     */
    public JacksonXmlCodecProvider() {
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(JACKSON_XML_CLASS, false, getClass().getClassLoader());
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
        return JacksonXmlCodec.defaults();
    }
}
