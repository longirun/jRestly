package ru.jrestly.fixtures;

import ru.jrestly.AuthProvider;
import ru.jrestly.Environment;
import ru.jrestly.ModuleInfo;
import ru.jrestly.http.RetryPolicy;

public class TestModuleInfo implements ModuleInfo {

    private final TestEnvironment environment;
    private final TestAuthProvider authProvider;
    private RetryPolicy retryPolicy = RetryPolicy.off();

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
    public <T> Environment<T> getEnvironment() {
        return (Environment<T>) environment;
    }

    @Override
    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    @Override
    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    public void setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public TestAuthProvider getTestAuthProvider() {
        return authProvider;
    }

    public TestEnvironment getTestEnvironment() {
        return environment;
    }
}
