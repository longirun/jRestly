package ru.jrestly;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.util.Mapper;

import java.util.List;

public class SimpleModuleInfo implements ModuleInfo {

    private final String name;
    private final String baseUrl;
    private final AuthProvider authProvider;
    private final ObjectMapper objectMapper;
    private final int connectTimeout;
    private final int socketTimeout;
    private final int connectionRequestTimeout;

    private SimpleModuleInfo(Builder builder) {
        this.name = builder.name;
        this.baseUrl = builder.baseUrl;
        this.authProvider = builder.authHeaderName != null
                ? new SimpleAuthProvider(builder.authHeaderName, builder.authHeaderValue)
                : new SimpleAuthProvider();
        this.objectMapper = builder.objectMapper;
        this.connectTimeout = builder.connectTimeout;
        this.socketTimeout = builder.socketTimeout;
        this.connectionRequestTimeout = builder.connectionRequestTimeout;
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
    public ObjectMapper getObjectMapper() {
        return objectMapper;
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

    public static class Builder {
        private String name = "default";
        private String baseUrl;
        private String authHeaderName;
        private String authHeaderValue;
        private ObjectMapper objectMapper = Mapper.defaults();
        private int connectTimeout = 5_000;
        private int socketTimeout = 30_000;
        private int connectionRequestTimeout = 5_000;

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

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
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

        public SimpleModuleInfo build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("baseUrl is required");
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

    private static class SimpleAuthProvider implements AuthProvider {
        private final String headerName;
        private final String headerValue;
        private String responseHeaderName;
        private String responseHeaderValue;
        private String manualHeaderName;
        private String manualHeaderValue;

        SimpleAuthProvider() {
            this.headerName = null;
            this.headerValue = null;
        }

        SimpleAuthProvider(String headerName, String headerValue) {
            this.headerName = headerName;
            this.headerValue = headerValue;
        }

        @Override
        public Pair<String, String> getAuthHeader() {
            if (manualHeaderName != null && manualHeaderValue != null) {
                return new ImmutablePair<>(manualHeaderName, manualHeaderValue);
            }
            if (responseHeaderName != null && responseHeaderValue != null) {
                return new ImmutablePair<>(responseHeaderName, responseHeaderValue);
            }
            if (headerName != null && headerValue != null) {
                return new ImmutablePair<>(headerName, headerValue);
            }
            return null;
        }

        @Override
        public void setHeaders(List<Pair<String, String>> headers) {
            if (responseHeaderName != null) {
                String capturedHeader = headers.stream()
                        .filter(h -> responseHeaderName.equalsIgnoreCase(h.getKey()))
                        .findFirst()
                        .map(Pair::getValue)
                        .orElse(null);

                // a successful capture means a fresh login: it legitimately overrides a manual update,
                // while a failed one keeps the working manual token intact
                if (capturedHeader != null) {
                    this.manualHeaderName = null;
                    this.manualHeaderValue = null;
                }
                this.responseHeaderValue = capturedHeader;
            }
        }

        @Override
        public void setHeaderName(String name) {
            this.responseHeaderName = name;
        }

        @Override
        public void updateAuthHeader(String name, String value) {
            this.manualHeaderName = name;
            this.manualHeaderValue = value;
        }

        @Override
        public boolean isAuthorized() {
            return headerName != null || responseHeaderValue != null || manualHeaderValue != null;
        }
    }
}
