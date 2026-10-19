package ru.jrestly.codec.yaml;

import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.codec.PayloadCodecProvider;

/**
 * Service provider interface (SPI) registration that makes {@link YamlCodec}
 * light up automatically when {@code jrestly-codec-yaml} is on the classpath
 * (ADR-0073).
 *
 * <p>With this provider a client needs no explicit
 * {@code JRestlyClient.builder().codec(...)} call: the YAML codec claims
 * {@code application/yaml} / {@code text/yaml} / {@code *+yaml} by itself,
 * while JSON remains the default. Users who want the core's fail-fast behavior
 * can pass an explicit {@code jsonCodec(...)}-only registry.
 */
public final class YamlCodecProvider implements PayloadCodecProvider {

    /** Jackson YAML's key class: its presence on the classpath is the availability probe. */
    private static final String JACKSON_YAML_CLASS = "com.fasterxml.jackson.dataformat.yaml.YAMLMapper";

    /** Priority of the official YAML adapter (ADR-0073): ties are broken by registration order. */
    public static final int PRIORITY = 50;

    /**
     * Creates the provider instance; {@link java.util.ServiceLoader}
     * instantiates it through the {@code META-INF/services} registration.
     */
    public YamlCodecProvider() {
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(JACKSON_YAML_CLASS, false, getClass().getClassLoader());
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
        return YamlCodec.defaults();
    }
}
