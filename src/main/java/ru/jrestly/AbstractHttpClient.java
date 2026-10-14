package ru.jrestly;

import ru.jrestly.annotation.Anonymous;
import ru.jrestly.annotation.Authorization;
import ru.jrestly.http.HttpTemplate;
import ru.jrestly.http.HttpTemplateBuilder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** The module configuration this client was created with. */
    protected ModuleInfo moduleInfo;

    /** The JDK HTTP client used for every exchange; created by {@link #createConnection()}. */
    protected HttpClient httpClient;

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

        this.httpClient = builder.build();
    }

    /**
     * Closes the underlying {@link HttpClient}; safe to call twice.
     *
     * <p>Deliberately not {@code AutoCloseable}: the client is an
     * app-lifetime singleton, and the interface would provoke per-request
     * try-with-resources usage.
     */
    public void close() {
        if (httpClient != null) {
            httpClient.close();
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
     * template built by {@link HttpTemplateBuilder} and dispatched via
     * {@link HttpTemplate#exchange()}.
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
                    HttpTemplate httpTemplate = builder.build();

                    return httpTemplate.exchange();
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
}
