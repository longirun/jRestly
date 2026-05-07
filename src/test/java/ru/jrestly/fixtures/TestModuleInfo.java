package ru.jrestly.fixtures;

import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.AuthProvider;
import ru.jrestly.Environment;
import ru.jrestly.ModuleInfo;

public class TestModuleInfo implements ModuleInfo {

    private final TestEnvironment environment;
    private final TestAuthProvider authProvider;

    public TestModuleInfo(String baseUrl) {
        this.environment = new TestEnvironment(baseUrl);
        this.authProvider = new TestAuthProvider();
    }

    @Override
    public String getName() {
        return "test-module";
    }

    @SuppressWarnings("unchecked")
    @Override
    public Environment<Pair<String, String>> getEnvironment() {
        return environment;
    }

    @Override
    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public TestAuthProvider getTestAuthProvider() {
        return authProvider;
    }

    public TestEnvironment getTestEnvironment() {
        return environment;
    }
}
