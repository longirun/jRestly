package ru.jrestly.cache;

import ru.jrestly.http.Header;
import ru.jrestly.http.WireResponse;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Default immutable implementation of {@link CachedResponse}.
 */
public final class SimpleCachedResponse implements CachedResponse {

    private final int statusCode;
    private final List<Header> headers;
    private final byte[] body;
    private final Instant cachedAt;
    private final long freshnessLifetimeSeconds;
    private final boolean noCache;
    private final boolean mustRevalidate;
    private final String etag;
    private final String lastModified;
    private final HttpClient.Version version;

    /**
     * Constructs a {@link SimpleCachedResponse}.
     *
     * @param statusCode HTTP status code
     * @param headers HTTP response headers
     * @param body payload bytes
     * @param cachedAt instant when response was cached
     * @param freshnessLifetimeSeconds freshness lifetime in seconds
     * @param noCache whether {@code no-cache} directive applies
     * @param mustRevalidate whether {@code must-revalidate} applies
     * @param etag entity tag validator, or {@code null}
     * @param lastModified last modified validator, or {@code null}
     * @param version HTTP protocol version
     */
    public SimpleCachedResponse(
            int statusCode,
            List<Header> headers,
            byte[] body,
            Instant cachedAt,
            long freshnessLifetimeSeconds,
            boolean noCache,
            boolean mustRevalidate,
            String etag,
            String lastModified,
            HttpClient.Version version
    ) {
        this.statusCode = statusCode;
        this.headers = headers != null ? Collections.unmodifiableList(new ArrayList<>(headers)) : List.of();
        this.body = body != null ? body.clone() : null;
        this.cachedAt = Objects.requireNonNull(cachedAt, "cachedAt");
        this.freshnessLifetimeSeconds = Math.max(0, freshnessLifetimeSeconds);
        this.noCache = noCache;
        this.mustRevalidate = mustRevalidate;
        this.etag = etag;
        this.lastModified = lastModified;
        this.version = version != null ? version : HttpClient.Version.HTTP_1_1;
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public List<Header> headers() {
        return headers;
    }

    @Override
    public byte[] body() {
        return body != null ? body.clone() : null;
    }

    @Override
    public Instant cachedAt() {
        return cachedAt;
    }

    @Override
    public long freshnessLifetimeSeconds() {
        return freshnessLifetimeSeconds;
    }

    @Override
    public boolean isFresh() {
        return isFresh(Instant.now());
    }

    @Override
    public boolean isFresh(Instant now) {
        if (noCache) {
            return false;
        }
        long ageSeconds = Math.max(0, Duration.between(cachedAt, now).toSeconds());
        return ageSeconds < freshnessLifetimeSeconds;
    }

    @Override
    public boolean mustRevalidate() {
        return mustRevalidate || noCache;
    }

    @Override
    public boolean hasValidators() {
        return etag != null || lastModified != null;
    }

    @Override
    public Optional<String> etag() {
        return Optional.ofNullable(etag);
    }

    @Override
    public Optional<String> lastModified() {
        return Optional.ofNullable(lastModified);
    }

    @Override
    public WireResponse toWireResponse() {
        return toWireResponse(Instant.now());
    }

    @Override
    public WireResponse toWireResponse(Instant now) {
        long ageSeconds = Math.max(0, Duration.between(cachedAt, now).toSeconds());
        List<Header> responseHeaders = new ArrayList<>();
        boolean ageReplaced = false;

        for (Header h : headers) {
            if (h.name().equalsIgnoreCase("Age")) {
                responseHeaders.add(new Header("Age", String.valueOf(ageSeconds)));
                ageReplaced = true;
            } else {
                responseHeaders.add(h);
            }
        }
        if (!ageReplaced) {
            responseHeaders.add(new Header("Age", String.valueOf(ageSeconds)));
        }

        return new WireResponse(
                statusCode,
                Collections.unmodifiableList(responseHeaders),
                body != null ? body.clone() : null,
                null,
                version,
                List.of()
        );
    }
}
