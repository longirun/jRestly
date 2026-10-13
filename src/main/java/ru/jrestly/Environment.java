package ru.jrestly;

/**
 * Per-client execution environment: the base URL for relative request paths
 * and the client's typed auth details.
 *
 * @param <T> the auth-details type the client's {@code AuthProvider} works with (e.g. {@code Header})
 */
public interface Environment<T> {
    /**
     * The base URL that relative request paths are resolved against.
     *
     * @return the base URL
     */
    String getUrl();

    /**
     * The client's current auth details.
     *
     * @return the auth details, or {@code null} when none are set
     */
    T getAuthDetails();

    /**
     * Stores the client's auth details.
     *
     * @param authDetails the auth details to store, or {@code null} to clear them
     */
    void setAuthDetails(T authDetails);
}
