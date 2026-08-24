package ru.jrestly;

import ru.jrestly.http.Header;

import java.util.List;

public interface AuthProvider {

    boolean isAuthorized();

    /**
     * Headers attached to every authorized request; empty list means
     * the client is not authorized and no auth headers are sent.
     */
    List<Header> getAuthHeaders();

    void updateAuthHeader(String name, String value);

    default void captureAuthDetails(String headerName, List<Header> responseHeaders) {
        // a missing header means the login response did not carry auth details:
        // no-op keeps the currently active token instead of silently deauthorizing the client
        responseHeaders.stream()
                .filter(header -> headerName.equalsIgnoreCase(header.name()))
                .findFirst()
                .ifPresent(header -> updateAuthHeader(headerName, header.value()));
    }
}
