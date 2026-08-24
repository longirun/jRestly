package ru.jrestly;

import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.http.AuthHeaderProvider;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.json.JsonCodecResolver;

public class SimpleModuleInfo implements ModuleInfo {

    private final String name;
    private final String baseUrl;
    private final AuthProvider authProvider;
    private final JsonCodec jsonCodec;
    private final int connectTimeout;
    private final int socketTimeout;
    private final int connectionRequestTimeout;
    private final boolean validateControllers;

    private SimpleModuleInfo(Builder builder) {
        this.name = builder.name;
        this.baseUrl = builder.baseUrl;
        this.authProvider = builder.authHeaderName != null
                ? new AuthHeaderProvider(builder.authHeaderName, builder.authHeaderValue)
                : new AuthHeaderProvider();
        this.jsonCodec = builder.jsonCodec;
        this.connectTimeout = builder.connectTimeout;
        this.socketTimeout = builder.socketTimeout;
        this.connectionRequestTimeout = builder.connectionRequestTimeout;
        this.validateControllers = builder.validateControllers;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getName() {
        return name;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Environment<T> getEnvironment() {
        return (Environment<T>) new SimpleEnvironment(baseUrl);
    }

    @Override
    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    @Override
    public JsonCodec getJsonCodec() {
        return jsonCodec;
    }

    @Override
    public int getConnectTimeout() {
        return connectTimeout;
    }

    @Override
    public int getSocketTimeout() {
        return socketTimeout;
    }

    @Override
    public int getConnectionRequestTimeout() {
        return connectionRequestTimeout;
    }

    @Override
    public boolean isValidateControllers() {
        return validateControllers;
    }

    public static class Builder {
        private String name = "default";
        private String baseUrl;
        private String authHeaderName;
        private String authHeaderValue;
        private JsonCodec jsonCodec;
        private int connectTimeout = 5_000;
        private int socketTimeout = 30_000;
        private int connectionRequestTimeout = 5_000;
        private boolean validateControllers = true;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder authHeader(String name, String value) {
            this.authHeaderName = name;
            this.authHeaderValue = value;
            return this;
        }

        public Builder jsonCodec(JsonCodec jsonCodec) {
            this.jsonCodec = jsonCodec;
            return this;
        }

        public Builder connectTimeout(int ms) {
            this.connectTimeout = ms;
            return this;
        }

        public Builder socketTimeout(int ms) {
            this.socketTimeout = ms;
            return this;
        }

        public Builder connectionRequestTimeout(int ms) {
            this.connectionRequestTimeout = ms;
            return this;
        }

        public Builder validateControllers(boolean validate) {
            this.validateControllers = validate;
            return this;
        }

        public SimpleModuleInfo build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("baseUrl is required");
            }
            if (jsonCodec == null) {
                jsonCodec = JsonCodecResolver.resolve();
            }
            return new SimpleModuleInfo(this);
        }
    }

    private static class SimpleEnvironment implements Environment<Pair<String, String>> {
        private final String url;
        private Pair<String, String> authDetails;

        SimpleEnvironment(String url) {
            this.url = url;
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public Pair<String, String> getAuthDetails() {
            return authDetails;
        }

        @Override
        public void setAuthDetails(Pair<String, String> authDetails) {
            this.authDetails = authDetails;
        }
    }
}
