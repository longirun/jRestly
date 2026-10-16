package ru.jrestly.codec;

import ru.jrestly.json.JsonCodec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Builds a client's {@link CodecRegistry}, merging explicitly configured
 * codecs with those auto-discovered through the {@link PayloadCodecProvider}
 * SPI (ADR-0060, phase 3).
 *
 * <p>Assembly order — which is also resolution priority — is:
 * <ol>
 *   <li>explicitly registered codecs, in registration order;</li>
 *   <li>auto-discovered codecs, ordered by {@link PayloadCodecProvider#priority()}
 *       (descending; ties keep the {@link ServiceLoader} order);</li>
 *   <li>the JSON codec as the default fallback for unclaimed media types.</li>
 * </ol>
 */
public final class PayloadCodecResolver {

    private PayloadCodecResolver() {
    }

    /**
     * Builds a registry around the given JSON codec, adding every codec
     * auto-discovered on the classpath.
     *
     * @param jsonCodec the JSON codec serving JSON media types and defaulting
     *                  for unclaimed ones; never {@code null}
     * @return the assembled registry
     */
    public static CodecRegistry registry(JsonCodec jsonCodec) {
        return registry(jsonCodec, List.of());
    }

    /**
     * Builds a registry around the given JSON codec: the explicit codecs are
     * registered first, then every codec auto-discovered on the classpath.
     *
     * @param jsonCodec      the JSON codec serving JSON media types and
     *                       defaulting for unclaimed ones; never {@code null}
     * @param explicitCodecs codecs registered explicitly, in priority order;
     *                       never {@code null}
     * @return the assembled registry
     */
    public static CodecRegistry registry(JsonCodec jsonCodec, List<PayloadCodec> explicitCodecs) {
        Objects.requireNonNull(jsonCodec, "jsonCodec");
        Objects.requireNonNull(explicitCodecs, "explicitCodecs");

        CodecRegistry.Builder builder = CodecRegistry.builder();
        for (PayloadCodec codec : explicitCodecs) {
            builder.codec(codec);
        }
        for (PayloadCodec codec : discover()) {
            builder.codec(codec);
        }
        return builder.defaultCodec(new JsonCodecAdapter(jsonCodec)).build();
    }

    /**
     * Every codec auto-discovered through {@link ServiceLoader}, ordered by
     * descending provider priority.
     *
     * @return an immutable list of discovered codecs
     */
    static List<PayloadCodec> discover() {
        return discoverFromSpi(ServiceLoader.load(
                PayloadCodecProvider.class, PayloadCodecResolver.class.getClassLoader()));
    }

    static List<PayloadCodec> discoverFromSpi(Iterable<PayloadCodecProvider> providers) {
        if (providers == null) {
            return List.of();
        }
        List<PayloadCodecProvider> candidates = new ArrayList<>();
        for (PayloadCodecProvider provider : providers) {
            if (provider != null) {
                candidates.add(provider);
            }
        }
        // List.sort is guaranteed stable: equal priorities keep the ServiceLoader order.
        candidates.sort(Comparator.comparingInt(PayloadCodecProvider::priority).reversed());

        List<PayloadCodec> codecs = new ArrayList<>();
        for (PayloadCodecProvider provider : candidates) {
            if (provider.isAvailable()) {
                PayloadCodec codec = provider.createCodec();
                if (codec != null) {
                    codecs.add(codec);
                }
            }
        }
        return List.copyOf(codecs);
    }
}
