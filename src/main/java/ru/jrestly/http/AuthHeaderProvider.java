package ru.jrestly.http;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.AuthProvider;

import java.util.List;

public abstract class AuthHeaderProvider implements AuthProvider {
    protected String headerName;
    protected String headerValue;

    @Override
    public void setHeaders(List<Pair<String, String>> headers) {
        this.headerValue = headers
                .stream()
                .filter(header -> headerName.equalsIgnoreCase(header.getKey()))
                .findFirst()
                .map(Pair::getValue)
                .orElse(null);
    }

    @Override
    public Pair<String, String> getAuthHeader() {
        if (headerName != null && headerValue != null) {
            return new ImmutablePair<>(headerName, headerValue);
        }

        return null;
    }

    @Override
    public void setHeaderName(String name) {
        this.headerName = name;
    }

    @Override
    public void updateAuthHeader(String name, String value) {
        this.headerName = name;
        this.headerValue = value;
    }
}
