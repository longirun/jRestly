package ru.jrestly;

import ru.jrestly.annotation.Anonymous;
import ru.jrestly.annotation.Authorization;
import ru.jrestly.http.HttpTemplateBuilder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base class for declarative HTTP clients. Owns a JDK {@link HttpClient}
 * and a cache of dynamic proxies, one per controller interface;
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

    /** The JDK HTTP client used for every exchange; created by {@link #createConnection()}. */
    protected HttpClient httpClient;

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
     * Creates the JDK {@link HttpClient} from the module configuration:
     * its connect timeout, HTTP version, and SSL/TLS configuration (ADR-0020),
     * with the JDK redirect policy set to {@code NEVER}.
     *
     * <p>Protected hook: a subclass may override it to build a different
     * client (e.g. with a network proxy or custom transport settings).
     */
    protected void createConnection() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(moduleInfo.getConnectTimeout()))
                .version(moduleInfo.getHttpVersion())
                .followRedirects(HttpClient.Redirect.NEVER);

        SSLContext sslContext = moduleInfo.getSslContext();
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }

        SSLParameters sslParameters = moduleInfo.getSslParameters();
        if (sslParameters != null) {
            builder.sslParameters(sslParameters);
        }

        CookieHandler cookieHandler = moduleInfo.getCookieHandler();
        if (cookieHandler != null) {
            builder.cookieHandler(cookieHandler);
        }

        ProxySelector proxySelector = moduleInfo.getProxySelector();
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }

        Authenticator authenticator = moduleInfo.getAuthenticator();
        if (authenticator != null) {
            builder.authenticator(authenticator);
        }

        Executor customExecutor = moduleInfo.getExecutor();
        if (customExecutor != null) {
            this.executor = customExecutor;
            builder.executor(customExecutor);
        } else if (moduleInfo.isVirtualThreads()) {
            this.managedExecutorService = Executors.newVirtualThreadPerTaskExecutor();
            this.executor = this.managedExecutorService;
            builder.executor(this.managedExecutorService);
        }

        this.httpClient = builder.build();
    }

    /**
     * Closes the underlying {@link HttpClient} and shuts down any internally
     * managed executor service (ADR-0026); safe to call twice.
     *
     * <p>Deliberately not {@code AutoCloseable}: the client is an
     * app-lifetime singleton, and the interface would provoke per-request
     * try-with-resources usage.
     */
    public void close() {
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

                    HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method, args, classInterface);
                    if (executor != null) {
                        builder.executor(executor);
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
        boolean isNotAuthorisation = method.getAnnotation(Authorization.class) == null;
        boolean hasNoAnonymousAnnotation = method.getAnnotation(Anonymous.class) == null;
        boolean authorized = moduleInfo.getAuthProvider().isAuthorized();

        return !authorized && isNotAuthorisation && hasNoAnonymousAnnotation;
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
}

