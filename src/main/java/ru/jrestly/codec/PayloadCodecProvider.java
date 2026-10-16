package ru.jrestly.codec;

/**
 * Service provider interface (SPI) for auto-discovering {@link PayloadCodec}s
 * via {@link java.util.ServiceLoader} (ADR-0060, phase 3).
 *
 * <p>A codec submodule or third-party adapter registers an implementation in
 * {@code META-INF/services/ru.jrestly.codec.PayloadCodecProvider}; a client
 * built without an explicit {@link ru.jrestly.codec.CodecRegistry} then picks
 * the codec up automatically, so plugging in a format needs no builder call.
 * Explicit registrations ({@code JRestlyClient.builder().codec(...)}) always
 * take priority, and replacing the whole registry
 * ({@code JRestlyClient.builder().codecs(...)}) disables auto-discovery
 * entirely.
 *
 * <p>The contract mirrors {@link ru.jrestly.engine.HttpEngineProvider}
 * (ADR-0058): a provider probes availability before it is consulted, and the
 * highest {@link #priority()} wins; ties keep the {@link java.util.ServiceLoader}
 * iteration order (the sort is stable).
 */
public interface PayloadCodecProvider {

    /**
     * Whether this provider is usable in the current runtime environment (for
     * example, its backing parser is present on the classpath). An unavailable
     * provider is skipped silently, so an optional submodule that is only
     * partially deployed never breaks a client.
     *
     * @return {@code true} if this provider can create a codec
     */
    boolean isAvailable();

    /**
     * Creates the codec this provider contributes to the client's registry.
     *
     * @return the codec, or {@code null} to contribute nothing
     */
    PayloadCodec createCodec();

    /**
     * The priority of this provider: discovery considers higher values first
     * (ADR-0060). It only orders competing auto-discovered codecs against each
     * other; explicitly registered codecs are always added before discovered
     * ones. The default priority is {@code 0}.
     *
     * @return the priority value, higher values are preferred
     */
    default int priority() {
        return 0;
    }
}
