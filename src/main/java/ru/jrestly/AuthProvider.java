package ru.jrestly;

import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

public interface AuthProvider {

    boolean isAuthorized();

    /**
     * Headers attached to every authorized request; empty list means
     * the client is not authorized and no auth headers are sent.
     */
    List<Pair<String, String>> getAuthHeaders();

    void updateAuthHeader(String name, String value);

    default void captureAuthDetails(String headerName, List<Pair<String, String>> responseHeaders) {
        // a missing header means the login response did not carry auth details:
        // no-op keeps the currently active token instead of silently deauthorizing the client
        responseHeaders.stream()
                .filter(header -> headerName.equalsIgnoreCase(header.getKey()))
                .findFirst()
                .ifPresent(header -> updateAuthHeader(headerName, header.getValue()));
    }
}
