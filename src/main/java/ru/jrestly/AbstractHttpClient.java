package ru.jrestly;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.jrestly.annotation.Anonymous;
import ru.jrestly.annotation.Authorization;
import ru.jrestly.http.HttpTemplate;
import ru.jrestly.http.HttpTemplateBuilder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractHttpClient {
    protected final Logger logger = LogManager.getLogger(getClass());

    protected final Map<Class<?>, Proxy> controllers = new HashMap<>();

    protected ModuleInfo moduleInfo;
    protected CloseableHttpClient httpClient;

    public AbstractHttpClient(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;

        createConnection();
    }

    protected ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    protected void login() {
    }

    protected void createConnection() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(moduleInfo.getConnectTimeout())
                .setSocketTimeout(moduleInfo.getSocketTimeout())
                .setConnectionRequestTimeout(moduleInfo.getConnectionRequestTimeout())
                .build();

        this.httpClient = HttpClients.custom()
                .setDefaultCookieStore(null)
                .disableRedirectHandling()
                .disableAuthCaching()
                .setDefaultRequestConfig(config)
                .build();
    }

    // Deliberately NOT AutoCloseable: the client is an app-lifetime singleton, and
    // the interface would provoke per-request try-with-resources usage
    public void close() {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                logger.error("Failed to close http connection", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> controllerClass) {
        return (T) controllers.computeIfAbsent(controllerClass, this::createController);
    }

    protected Proxy createController(Class<?> classInterface) {
        validateController(classInterface);

        return (Proxy) Proxy.newProxyInstance(
                getClassLoader(),
                new Class[] {classInterface},
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

        logger.warn("Controller {} is invalid, eager validation is disabled; failing methods will throw on call: {}",
                classInterface.getName(), problems);
    }

    protected boolean isLoginInBackgroundNeeded(Method method) {
        boolean isNotAuthorisation = method.getAnnotation(Authorization.class) == null;
        boolean hasNoAnonymousAnnotation = method.getAnnotation(Anonymous.class) == null;
        boolean authorized = moduleInfo.getAuthProvider().isAuthorized();

        return !authorized && isNotAuthorisation && hasNoAnonymousAnnotation;
    }

    public ModuleInfo getModuleInfo() {
        return moduleInfo;
    }

    public void updateAuthHeader(String name, String value) {
        moduleInfo.getAuthProvider().updateAuthHeader(name, value);
    }
}
