package ru.jrestly.engine;

import ru.jrestly.ModuleInfo;

import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Resolves the {@link HttpEngine} used by a client.
 *
 * <p>Resolution strategy:
 * <ol>
 *   <li>If an explicit {@link HttpEngine} is configured in {@link ModuleInfo#getHttpEngine()}, it always wins.</li>
 *   <li>Otherwise, inspects {@link ServiceLoader} for registered {@link HttpEngineProvider} instances.
 *       The first provider reporting {@link HttpEngineProvider#isAvailable()} provides the engine.</li>
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
            for (HttpEngineProvider provider : providers) {
                if (provider != null && provider.isAvailable()) {
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
