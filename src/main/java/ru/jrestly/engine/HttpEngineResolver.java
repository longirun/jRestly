package ru.jrestly.engine;

import ru.jrestly.ModuleInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Resolves the {@link HttpEngine} used by a client.
 *
 * <p>Resolution strategy:
 * <ol>
 *   <li>If an explicit {@link HttpEngine} is configured in {@link ModuleInfo#getHttpEngine()}, it always wins.</li>
 *   <li>Otherwise, inspects {@link ServiceLoader} for registered {@link HttpEngineProvider} instances.
 *       The available provider with the highest {@link HttpEngineProvider#priority()} provides the engine
 *       (ties keep the {@link ServiceLoader} iteration order — the sort is stable, ADR-0058).</li>
 *   <li>If no custom provider is registered or available, falls back to the standard zero-dependency
 *       {@link JdkHttpEngine#create(ModuleInfo)}.</li>
 * </ol>
 */
public final class HttpEngineResolver {

    private HttpEngineResolver() {
    }

    /**
     * Resolves the {@link HttpEngine} for the given module configuration.
     *
     * @param moduleInfo the client module configuration, must not be {@code null}
     * @return the resolved HTTP engine
     */
    public static HttpEngine resolve(ModuleInfo moduleInfo) {
        Objects.requireNonNull(moduleInfo, "moduleInfo");
        if (moduleInfo.getHttpEngine() != null) {
            return moduleInfo.getHttpEngine();
        }

        return resolveFromSpi(moduleInfo, ServiceLoader.load(HttpEngineProvider.class, HttpEngineResolver.class.getClassLoader()));
    }

    static HttpEngine resolveFromSpi(ModuleInfo moduleInfo, Iterable<HttpEngineProvider> providers) {
        if (providers != null) {
            List<HttpEngineProvider> candidates = new ArrayList<>();
            for (HttpEngineProvider provider : providers) {
                if (provider != null) {
                    candidates.add(provider);
                }
            }
            // List.sort is guaranteed stable: equal priorities keep the ServiceLoader order.
            candidates.sort(Comparator.comparingInt(HttpEngineProvider::priority).reversed());
            for (HttpEngineProvider provider : candidates) {
                if (provider.isAvailable()) {
                    HttpEngine engine = provider.createEngine(moduleInfo);
                    if (engine != null) {
                        return engine;
                    }
                }
            }
        }
        return JdkHttpEngine.create(moduleInfo);
    }
}
