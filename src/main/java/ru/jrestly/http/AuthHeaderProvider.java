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
 *
 * <p>Only a <em>complete</em> name/value pair (both parts non-{@code null})
 * counts as a credential: an incomplete pair is never sent and never
 * reports the client as {@link #isAuthorized() authorized}. The captured
 * pair is held as a single immutable {@link Header} published through a
 * {@code volatile} field, so concurrent capture and reads observe an atomic
 * pair rather than a torn name/value mix — the provider is meant to be
 * shared by an app-lifetime client whose credential may be refreshed from a
 * background thread.
 */
public class AuthHeaderProvider implements AuthProvider {

    private final Header initialHeader;

    private volatile Header capturedHeader;

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
        this.initialHeader = complete(headerName, headerValue);
    }

    @Override
    public List<Header> getAuthHeaders() {
        Header captured = capturedHeader;
        if (captured != null) {
            return List.of(captured);
        }

        return initialHeader != null ? List.of(initialHeader) : List.of();
    }

    @Override
    public void updateAuthHeader(String name, String value) {
        this.capturedHeader = complete(name, value);
    }

    @Override
    public boolean isAuthorized() {
        return capturedHeader != null || initialHeader != null;
    }

    private static Header complete(String name, String value) {
        return name != null && value != null ? new Header(name, value) : null;
    }
}
