package ru.jrestly.http;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.AuthProvider;

import java.util.List;
import java.util.stream.Collectors;

public abstract class AuthHeaderProvider implements AuthProvider {
    protected String headerName;
    protected String value;

    @Override
    public void setHeaders(List<Pair<String, String>> headers) {
        this.value = headers
                .stream()
                .filter(header -> headerName.equalsIgnoreCase(header.getKey()))
                .findFirst()
                .map(Pair::getValue)
                .orElse(null);
    }

    @Override
    public Pair<String, String> getAuthHeader() {
        if (headerName != null && value != null) {
            return new ImmutablePair<>(headerName, value);
        }

        return null;
    }

    @Override
    public void setHeaderName(String name) {
        this.headerName = name;
    }
}
