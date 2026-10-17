package ru.jrestly.cache;

import ru.jrestly.http.Header;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory LRU implementation of {@link HttpCache} conforming to RFC 9111.
 */
public class InMemoryHttpCache implements HttpCache {

    private static final int DEFAULT_MAX_ENTRIES = 100;

    private final int maxEntries;
    private final Map<String, CachedResponse> cache;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Constructs an in-memory cache with the default maximum capacity of 100 entries.
     */
    public InMemoryHttpCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Constructs an in-memory cache with the specified maximum capacity.
     *
     * @param maxEntries maximum number of cached entries before LRU eviction, must be &gt; 0
     * @throws IllegalArgumentException if {@code maxEntries <= 0}
     */
    public InMemoryHttpCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, was: " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedResponse> eldest) {
                return size() > maxEntries;
            }
        };
    }

    @Override
    public Optional<CachedResponse> get(WireRequest request) {
        if (request == null || request.uri() == null) {
            return Optional.empty();
        }
        if (request.method() != HttpMethod.GET) {
            return Optional.empty();
        }

        CacheControl reqCc = CacheControl.parse(request.headers());
        if (reqCc.noStore()) {
            return Optional.empty();
        }

        String key = normalizeKey(request.uri());
        lock.lock();
        try {
            CachedResponse entry = cache.get(key);
            return Optional.ofNullable(entry);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(WireRequest request, WireResponse response) {
        if (request == null || response == null || request.uri() == null) {
            return;
        }
        if (request.method() != HttpMethod.GET) {
            return;
        }
        int status = response.statusCode();
        if (status != 200 && status != 301 && status != 404) {
            return;
        }
        if (response.body() == null) {
            return;
        }

        CacheControl reqCc = CacheControl.parse(request.headers());
        if (reqCc.noStore()) {
            return;
        }

        CacheControl resCc = CacheControl.parse(response.headers());
        if (resCc.noStore()) {
            return;
        }

        Instant now = Instant.now();
        Long maxAge = resCc.maxAgeSeconds();
        Instant expires = parseExpires(response.headers());
        Instant date = parseDate(response.headers());

        long freshnessLifetime = 0;
        if (maxAge != null) {
            freshnessLifetime = maxAge;
        } else if (expires != null) {
            long base = date != null ? date.getEpochSecond() : now.getEpochSecond();
            freshnessLifetime = Math.max(0, expires.getEpochSecond() - base);
        }

        String etag = findHeader(response.headers(), "ETag");
        String lastModified = findHeader(response.headers(), "Last-Modified");

        if (maxAge == null && expires == null && etag == null && lastModified == null) {
            return;
        }

        SimpleCachedResponse entry = new SimpleCachedResponse(
                status,
                response.headers(),
                response.body(),
                now,
                freshnessLifetime,
                resCc.noCache() || reqCc.noCache(),
                resCc.mustRevalidate(),
                etag,
                lastModified,
                response.version()
        );

        String key = normalizeKey(request.uri());
        lock.lock();
        try {
            cache.put(key, entry);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public WireResponse update(WireRequest request, WireResponse response304) {
        if (request == null || response304 == null || request.uri() == null) {
            return null;
        }
        String key = normalizeKey(request.uri());
        lock.lock();
        try {
            CachedResponse old = cache.get(key);
            if (old == null) {
                return null;
            }

            List<Header> mergedHeaders = mergeHeaders(old.headers(), response304.headers());
            CacheControl resCc = CacheControl.parse(mergedHeaders);
            Instant now = Instant.now();

            Long maxAge = resCc.maxAgeSeconds();
            Instant expires = parseExpires(mergedHeaders);
            Instant date = parseDate(mergedHeaders);

            long freshnessLifetime = old.freshnessLifetimeSeconds();
            if (maxAge != null) {
                freshnessLifetime = maxAge;
            } else if (expires != null) {
                long base = date != null ? date.getEpochSecond() : now.getEpochSecond();
                freshnessLifetime = Math.max(0, expires.getEpochSecond() - base);
            }

            String etag = findHeader(mergedHeaders, "ETag");
            String lastModified = findHeader(mergedHeaders, "Last-Modified");

            SimpleCachedResponse updated = new SimpleCachedResponse(
                    old.statusCode(),
                    mergedHeaders,
                    old.body(),
                    now,
                    freshnessLifetime,
                    resCc.noCache(),
                    resCc.mustRevalidate(),
                    etag,
                    lastModified,
                    response304.version() != null ? response304.version() : old.toWireResponse().version()
            );

            cache.put(key, updated);
            return updated.toWireResponse(now);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void invalidate(URI uri) {
        if (uri == null) {
            return;
        }
        String key = normalizeKey(uri);
        lock.lock();
        try {
            cache.remove(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            cache.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the maximum entries capacity of this cache.
     *
     * @return max entries
     */
    public int maxEntries() {
        return maxEntries;
    }

    private static String normalizeKey(URI uri) {
        return uri.normalize().toASCIIString();
    }

    private static String findHeader(List<Header> headers, String name) {
        if (headers == null) return null;
        for (Header h : headers) {
            if (h.name().equalsIgnoreCase(name)) {
                return h.value();
            }
        }
        return null;
    }

    private static Instant parseExpires(List<Header> headers) {
        String val = findHeader(headers, "Expires");
        return parseHttpDate(val);
    }

    private static Instant parseDate(List<Header> headers) {
        String val = findHeader(headers, "Date");
        return parseHttpDate(val);
    }

    private static Instant parseHttpDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static List<Header> mergeHeaders(List<Header> oldHeaders, List<Header> newHeaders) {
        List<Header> result = new ArrayList<>();
        List<String> updatedNames = new ArrayList<>();

        if (newHeaders != null) {
            for (Header nh : newHeaders) {
                result.add(nh);
                updatedNames.add(nh.name().toLowerCase(java.util.Locale.ROOT));
            }
        }

        if (oldHeaders != null) {
            for (Header oh : oldHeaders) {
                String lower = oh.name().toLowerCase(java.util.Locale.ROOT);
                // Retain representation headers (content-type, content-length) unless explicitly updated in 304
                if (!updatedNames.contains(lower)) {
                    result.add(oh);
                }
            }
        }

        return Collections.unmodifiableList(result);
    }
}
