package ru.jrestly;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JRestlyClient extends AbstractHttpClient {

    public JRestlyClient(ModuleInfo moduleInfo) {
        super(moduleInfo);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    protected void login() {
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> controllerClass) {
        return (T) controllers.computeIfAbsent(controllerClass, this::createController);
    }

    public static class Builder {
        private final SimpleModuleInfo.Builder moduleInfoBuilder = SimpleModuleInfo.builder();

        public Builder name(String name) {
            moduleInfoBuilder.name(name);
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            moduleInfoBuilder.baseUrl(baseUrl);
            return this;
        }

        public Builder authHeader(String name, String value) {
            moduleInfoBuilder.authHeader(name, value);
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            moduleInfoBuilder.objectMapper(objectMapper);
            return this;
        }

        public Builder connectTimeout(int ms) {
            moduleInfoBuilder.connectTimeout(ms);
            return this;
        }

        public Builder socketTimeout(int ms) {
            moduleInfoBuilder.socketTimeout(ms);
            return this;
        }

        public Builder connectionRequestTimeout(int ms) {
            moduleInfoBuilder.connectionRequestTimeout(ms);
            return this;
        }

        public JRestlyClient build() {
            return new JRestlyClient(moduleInfoBuilder.build());
        }
    }
}
