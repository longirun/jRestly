package ru.jrestly;

import ru.jrestly.http.Header;

import java.util.List;

/**
 * Supplies the authentication state of a client: whether it is authorized,
 * which headers to attach to every authorized request, and how a fresh
 * credential is written back ({@link #captureAuthDetails(String, List)},
 * driven by a method's {@code @SetAuthDetails}).
 */
public interface AuthProvider {

    /**
     * Whether the client currently carries valid credentials.
     *
     * @return {@code true} when the client is authorized
     */
    boolean isAuthorized();

    /**
     * Headers attached to every authorized request; empty list means
     * the client is not authorized and no auth headers are sent.
     *
     * @return the auth headers, or an empty list when not authorized
     */
    List<Header> getAuthHeaders();

    /**
     * Writes a credential back into the provider.
     *
     * @param name the header name carrying the credential
     * @param value the new credential value
     */
    void updateAuthHeader(String name, String value);

    /**
     * Captures a fresh credential from a response: when {@code headerName}
     * is present among the response headers, its value replaces the stored
     * credential via {@link #updateAuthHeader(String, String)}; an absent
     * header leaves the existing token untouched.
     *
     * @param headerName the response header carrying the credential
     * @param responseHeaders the response headers to look up
     */
    default void captureAuthDetails(String headerName, List<Header> responseHeaders) {
        // If the header is absent from the response, retain the existing auth token
        responseHeaders.stream()
                .filter(header -> headerName.equalsIgnoreCase(header.name()))
                .findFirst()
                .ifPresent(header -> updateAuthHeader(headerName, header.value()));
    }
}
