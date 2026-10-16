package ru.jrestly;

import ru.jrestly.annotation.Anonymous;
import ru.jrestly.annotation.Authorization;
import ru.jrestly.http.HttpTemplateBuilder;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base class for declarative HTTP clients. Owns an HTTP transport
 * ({@link ru.jrestly.engine.HttpEngine}, ADR-0052) and a cache of dynamic
 * proxies, one per controller interface;
 * {@link JRestlyClient} is the ready-to-use subclass.
 */
public abstract class AbstractHttpClient {
    /** The per-subclass logger, named after the concrete class. */
    protected final System.Logger logger = System.getLogger(getClass().getName());

    /** The created controller proxies, keyed by the controller interface. */
    protected final Map<Class<?>, Proxy> controllers = new HashMap<>();

    /** Cached instances of declarative interceptors declared via @WithInterceptor. */
    protected final Map<Class<?>, Object> interceptorInstances = new ConcurrentHashMap<>();

    /** The module configuration this client was created with. */
    protected ModuleInfo moduleInfo;

    /** The JDK HTTP client; non-null when the resolved engine is a {@link ru.jrestly.engine.JdkHttpEngine} (ADR-0052). */
    protected HttpClient httpClient;

    /** The HTTP engine used for every exchange (ADR-0052); created by {@link #createConnection()}. */
    protected ru.jrestly.engine.HttpEngine httpEngine;

    /** The executor used for asynchronous tasks; either from module configuration or managed virtual threads. */
    protected Executor executor;

    /** The internally managed executor service (e.g. for virtual threads), closed on {@link #close()}. */
    protected ExecutorService managedExecutorService;

    // this escapes by design: the protected createConnection() hook is API,
    // and overriding subclasses need the live client right from the constructor (ADR-0013)
    /**
     * Stores the module configuration, then creates the underlying
     * connection via {@link #createConnection()}.
     *
     * @param moduleInfo the module configuration for the client
     */
    @SuppressWarnings("this-escape")
    public AbstractHttpClient(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;

        createConnection();
        if (this.httpEngine == null && this.httpClient != null) {
            this.httpEngine = new ru.jrestly.engine.JdkHttpEngine(this.httpClient, true);
        }
    }

    /**
     * The class loader that controller proxies are created with.
     *
     * @return the class loader of the concrete client class
     */
    protected ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    /**
     * Acquires credentials in the background; runs before a dispatch that
     * needs login (see {@link #isLoginInBackgroundNeeded(Method)}). A
     * no-op by default.
     *
     * <p>Protected hook: a subclass overrides it to implement login.
     */
    protected void login() {
    }

    /**
     * Creates the HTTP transport for the module configuration (ADR-0052,
     * ADR-0058). Resolution order:
     * <ol>
     *   <li>an explicit {@link ModuleInfo#getHttpEngine()} always wins;</li>
     *   <li>otherwise {@link ru.jrestly.engine.HttpEngineResolver} inspects
     *       {@link java.util.ServiceLoader} for registered
     *       {@link ru.jrestly.engine.HttpEngineProvider}s (e.g. the external
     *       OkHttp/Apache submodules) and picks the highest-priority available one;</li>
     *   <li>with no provider available, the resolver falls back to the
     *       zero-dependency JDK engine.</li>
     * </ol>
     *
     * <p>The executor for asynchronous pipeline stages (ADR-0026) is resolved
     * here too: a custom {@link ModuleInfo#getExecutor()} wins, otherwise
     * virtual threads are used when {@link ModuleInfo#isVirtualThreads()} is
     * enabled, with the managed service shut down on {@link #close()}.
     *
     * <p>Protected hook: a subclass may override it to build a different
     * client (e.g. with a network proxy or custom transport settings).
     */
    protected void createConnection() {
        Executor customExecutor = moduleInfo.getExecutor();
        if (customExecutor != null) {
            this.executor = customExecutor;
        } else if (moduleInfo.isVirtualThreads()) {
            this.managedExecutorService = Executors.newVirtualThreadPerTaskExecutor();
            this.executor = this.managedExecutorService;
        }

        this.httpEngine = ru.jrestly.engine.HttpEngineResolver.resolve(moduleInfo);
        if (this.httpEngine instanceof ru.jrestly.engine.JdkHttpEngine jdkEngine) {
            this.httpClient = jdkEngine.getHttpClient();
        }
    }

    /**
     * Closes the underlying {@link HttpClient} or {@link ru.jrestly.engine.HttpEngine} and shuts down
     * any internally managed executor service (ADR-0026); safe to call twice.
     *
     * <p>Deliberately not {@code AutoCloseable}: the client is an
     * app-lifetime singleton, and the interface would provoke per-request
     * try-with-resources usage.
     */
    public void close() {
        if (httpEngine != null) {
            httpEngine.close();
        }
        if (httpClient != null) {
            httpClient.close();
        }
        if (managedExecutorService != null) {
            managedExecutorService.close();
        }
    }

    /**
     * Returns a proxy implementing the given controller interface,
     * creating and caching it on first use.
     *
     * @param <T> the controller interface type
     * @param controllerClass the controller interface to proxy
     * @return the cached proxy for the interface
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> controllerClass) {
        return (T) controllers.computeIfAbsent(controllerClass, this::createController);
    }

    /**
     * Validates the controller interface and creates its proxy. The
     * validation runs up front: with {@link ModuleInfo#isValidateControllers()}
     * on, a failure throws {@link ControllerValidationException};
     * otherwise the problems are logged and failing methods throw on call.
     *
     * <p>Protected hook: the proxy dispatches each invocation through the
     * HTTP client — an optional {@link #login()}, then the request
     * pipeline built and dispatched via {@link HttpTemplateBuilder#execute()}.
     *
     * @param classInterface the controller interface to proxy
     * @return the dynamic proxy for the interface
     */
    protected Proxy createController(Class<?> classInterface) {
        validateController(classInterface);

        return (Proxy) Proxy.newProxyInstance(
                getClassLoader(),
                new Class<?>[] {classInterface},
                (proxy, method, args) -> {
                    if (isLoginInBackgroundNeeded(method)) {
                        login();
                    }

                    HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpEngine, method, args, classInterface);
                    if (executor != null) {
                        builder.executor(executor);
                    }
                    if (moduleInfo.getLogger() != null) {
                        builder.logger(moduleInfo.getLogger());
                    }
                    builder.interceptorCache(interceptorInstances);

                    return builder.execute();
                }
        );
    }

    private void validateController(Class<?> classInterface) {
        List<String> problems = ControllerValidator.validate(classInterface);
        if (problems.isEmpty()) {
            return;
        }

        if (moduleInfo.isValidateControllers()) {
            throw new ControllerValidationException(classInterface, problems);
        }

        logger.log(System.Logger.Level.WARNING,
                "Controller " + classInterface.getName()
                        + " is invalid, eager validation is disabled; failing methods will throw on call: " + problems);
    }

    /**
     * Whether the dispatch of the method must first run {@link #login()}:
     * the module is not authorized yet, and the method neither marks an
     * authentication endpoint ({@code @Authorization}) nor opts out of
     * credentials ({@code @Anonymous}).
     *
     * @param method the controller method about to be dispatched
     * @return {@code true} when a background login is needed
     */
    protected boolean isLoginInBackgroundNeeded(Method method) {
        boolean authorized = moduleInfo.getAuthProvider().isAuthorized();
        if (authorized) {
            return false;
        }
        boolean isNotAuthorisation = method.getAnnotation(Authorization.class) == null;
        boolean hasNoAnonymousAnnotation = method.getAnnotation(Anonymous.class) == null;

        return isNotAuthorisation && hasNoAnonymousAnnotation;
    }

    /**
     * The module configuration this client was created with.
     *
     * @return the module configuration
     */
    public ModuleInfo getModuleInfo() {
        return moduleInfo;
    }

    /**
     * Updates a stored auth header through the module's
     * {@link AuthProvider}.
     *
     * @param name the header name carrying the credential
     * @param value the new credential value
     */
    public void updateAuthHeader(String name, String value) {
        moduleInfo.getAuthProvider().updateAuthHeader(name, value);
    }

    /**
     * Returns the executor used by this client for asynchronous tasks, or
     * {@code null} if using the platform default (ADR-0026).
     *
     * @return the client's executor, or {@code null}
     */
    public Executor getExecutor() {
        return executor;
    }

    /**
     * Returns the cookie handler used by this client, or {@code null} if none is configured (ADR-0032).
     *
     * @return the cookie handler, or {@code null}
     */
    public CookieHandler getCookieHandler() {
        return moduleInfo != null ? moduleInfo.getCookieHandler() : null;
    }

    /**
     * Returns the cookie store if this client is configured with a {@link CookieManager} (ADR-0032).
     *
     * @return optional containing the cookie store, or empty if no CookieManager is active
     */
    public Optional<CookieStore> getCookieStore() {
        if (moduleInfo != null && moduleInfo.getCookieHandler() instanceof CookieManager cm) {
            return Optional.of(cm.getCookieStore());
        }
        return Optional.empty();
    }

    /**
     * Returns the proxy selector configured for this client, or {@code null} if default (ADR-0037).
     *
     * @return the proxy selector, or {@code null}
     */
    public ProxySelector getProxySelector() {
        return moduleInfo != null ? moduleInfo.getProxySelector() : null;
    }

    /**
     * Returns the authenticator configured for this client, or {@code null} if none (ADR-0037).
     *
     * @return the authenticator, or {@code null}
     */
    public Authenticator getAuthenticator() {
        return moduleInfo != null ? moduleInfo.getAuthenticator() : null;
    }

    /**
     * Returns the HTTP transport engine used by this client (ADR-0052).
     *
     * @return the HTTP engine
     */
    public ru.jrestly.engine.HttpEngine getHttpEngine() {
        return httpEngine;
    }

    /**
     * Returns the underlying JDK HTTP client, or {@code null} if a non-JDK engine is configured.
     *
     * @return the JDK HTTP client, or {@code null}
     */
    public HttpClient getHttpClient() {
        if (httpClient != null) {
            return httpClient;
        }
        if (httpEngine instanceof ru.jrestly.engine.JdkHttpEngine jdk) {
            return jdk.getHttpClient();
        }
        return null;
    }
}

