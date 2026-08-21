package ru.jrestly.http;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.AuthProvider;

import java.util.List;

public class AuthHeaderProvider implements AuthProvider {

    private final String initialHeaderName;
    private final String initialHeaderValue;

    private String headerName;
    private String headerValue;

    public AuthHeaderProvider() {
        this(null, null);
    }

    public AuthHeaderProvider(String headerName, String headerValue) {
        this.initialHeaderName = headerName;
        this.initialHeaderValue = headerValue;
    }

    @Override
    public List<Pair<String, String>> getAuthHeaders() {
        if (headerName != null && headerValue != null) {
            return List.of(new ImmutablePair<>(headerName, headerValue));
        }

        if (initialHeaderName != null && initialHeaderValue != null) {
            return List.of(new ImmutablePair<>(initialHeaderName, initialHeaderValue));
        }

        return List.of();
    }

    @Override
    public void updateAuthHeader(String name, String value) {
        this.headerName = name;
        this.headerValue = value;
    }

    @Override
    public boolean isAuthorized() {
        return initialHeaderName != null || headerValue != null;
    }
}
