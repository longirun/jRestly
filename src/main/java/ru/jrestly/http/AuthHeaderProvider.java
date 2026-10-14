package ru.jrestly.http;

import ru.jrestly.AuthProvider;

import java.util.List;

/**
 * The default {@link AuthProvider}: a single credential held as a header
 * name/value pair.
 *
 * <p>Installed by {@link ru.jrestly.SimpleModuleInfo} when the module does not
 * configure a custom provider — preloaded with the module's {@code authHeader}
 * pair when one is set, empty otherwise. The pair given at construction is
 * the initial credential; a fresh pair written back via
 * {@link #updateAuthHeader(String, String)} (the credential capture driven
 * by a method's {@code @SetAuthDetails}) takes precedence while both of its
 * parts are set.
 */
public class AuthHeaderProvider implements AuthProvider {

    private final String initialHeaderName;
    private final String initialHeaderValue;

    private String headerName;
    private String headerValue;

    /**
     * Creates a provider without an initial credential: it stays
     * unauthorized until a credential is written back via
     * {@link #updateAuthHeader(String, String)}.
     */
    public AuthHeaderProvider() {
        this(null, null);
    }

    /**
     * Creates a provider preloaded with the initial credential; the pair is
     * sent while both of its parts are set.
     *
     * @param headerName the initial credential header name, or {@code null}
     * @param headerValue the initial credential value, or {@code null}
     */
    public AuthHeaderProvider(String headerName, String headerValue) {
        this.initialHeaderName = headerName;
        this.initialHeaderValue = headerValue;
    }

    @Override
    public List<Header> getAuthHeaders() {
        if (headerName != null && headerValue != null) {
            return List.of(new Header(headerName, headerValue));
        }

        if (initialHeaderName != null && initialHeaderValue != null) {
            return List.of(new Header(initialHeaderName, initialHeaderValue));
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
