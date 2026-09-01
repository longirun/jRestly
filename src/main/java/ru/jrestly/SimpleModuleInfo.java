package ru.jrestly;

import ru.jrestly.http.AuthHeaderProvider;
import ru.jrestly.http.Header;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.json.JsonCodecResolver;

import java.net.http.HttpClient;

public class SimpleModuleInfo implements ModuleInfo {

    private final String name;
    private final String baseUrl;
    private final AuthProvider authProvider;
    private final JsonCodec jsonCodec;
    private final int connectTimeout;
    private final int socketTimeout;
    private final HttpClient.Version httpVersion;
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
        this.httpVersion = builder.httpVersion;
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
    public HttpClient.Version getHttpVersion() {
        return httpVersion;
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
        private HttpClient.Version httpVersion = HttpClient.Version.HTTP_2;
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

        public Builder httpVersion(HttpClient.Version httpVersion) {
            this.httpVersion = java.util.Objects.requireNonNull(httpVersion, "httpVersion");
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

    private static class SimpleEnvironment implements Environment<Header> {
        private final String url;
        private Header authDetails;

        SimpleEnvironment(String url) {
            this.url = url;
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public Header getAuthDetails() {
            return authDetails;
        }

        @Override
        public void setAuthDetails(Header authDetails) {
            this.authDetails = authDetails;
        }
    }
}
