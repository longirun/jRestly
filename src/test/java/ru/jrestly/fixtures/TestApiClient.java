package ru.jrestly.fixtures;

import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.AbstractHttpClient;
import ru.jrestly.ModuleInfo;

import java.lang.reflect.Proxy;

public class TestApiClient extends AbstractHttpClient {

    public TestApiClient(ModuleInfo moduleInfo) {
        super(moduleInfo);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> controllerClass) {
        return (T) controllers.computeIfAbsent(controllerClass, this::createController);
    }

    @Override
    protected ClassLoader getClassLoader() {
        return TestApiClient.class.getClassLoader();
    }

    @Override
    protected void login() {
        Pair<String, String> authHeader = moduleInfo.getAuthProvider().getAuthHeader();
        if (authHeader != null) {
            moduleInfo.getEnvironment().setAuthDetails(authHeader);
        }
    }
}
