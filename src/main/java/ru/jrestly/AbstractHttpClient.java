package ru.jrestly;

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
import java.util.Map;

public abstract class AbstractHttpClient {
    protected final Logger logger = LogManager.getLogger(getClass());

    protected final Map<Class<?>, Proxy> controllers = new HashMap<>();

    protected ModuleInfo moduleInfo;
    protected CloseableHttpClient httpClient;

    public AbstractHttpClient(ModuleInfo moduleInfo) {
        this.moduleInfo = moduleInfo;

        createConnection();

        createShutdownHook();
    }

    protected abstract ClassLoader getClassLoader();
    protected abstract void login();

    protected void createConnection() {
        this.httpClient = HttpClients.custom()
                .setDefaultCookieStore(null)
                .disableRedirectHandling()
                .disableAuthCaching()
                .build();
    }

    protected void createShutdownHook() {
        Thread hook = new Thread(this::close);

        Runtime.getRuntime().addShutdownHook(hook);
    }

    public void close() {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                logger.error("Failed to close http connection", e);
            }
        }
    }

    protected Proxy createController(Class<?> classInterface) {
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

    protected boolean isLoginInBackgroundNeeded(Method method) {
        boolean isNotAuthorisation = method.getAnnotation(Authorization.class) == null;
        boolean hasNoAnonymousAnnotation = method.getAnnotation(Anonymous.class) == null;
        boolean authorized = moduleInfo.getAuthProvider().isAuthorized();

        return !authorized && isNotAuthorisation && hasNoAnonymousAnnotation;
    }

    public ModuleInfo getModuleInfo() {
        return moduleInfo;
    }
}
