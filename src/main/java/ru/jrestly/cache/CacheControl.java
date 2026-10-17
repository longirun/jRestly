package ru.jrestly.cache;

import ru.jrestly.http.Header;

import java.util.List;
import java.util.Locale;

/**
 * Parsed representation of RFC 9111 {@code Cache-Control} header directives.
 */
public final class CacheControl {

    private static final CacheControl EMPTY = new CacheControl(false, false, false, null, null, false, false);

    private final boolean noCache;
    private final boolean noStore;
    private final boolean mustRevalidate;
    private final Long maxAgeSeconds;
    private final Long staleWhileRevalidateSeconds;
    private final boolean isPrivate;
    private final boolean isPublic;

    /**
     * Constructs a parsed {@link CacheControl} instance.
     *
     * @param noCache whether {@code no-cache} directive is present
     * @param noStore whether {@code no-store} directive is present
     * @param mustRevalidate whether {@code must-revalidate} directive is present
     * @param maxAgeSeconds the {@code max-age} value in seconds, or {@code null}
     * @param staleWhileRevalidateSeconds the {@code stale-while-revalidate} value in seconds, or {@code null}
     * @param isPrivate whether {@code private} directive is present
     * @param isPublic whether {@code public} directive is present
     */
    public CacheControl(
            boolean noCache,
            boolean noStore,
            boolean mustRevalidate,
            Long maxAgeSeconds,
            Long staleWhileRevalidateSeconds,
            boolean isPrivate,
            boolean isPublic
    ) {
        this.noCache = noCache;
        this.noStore = noStore;
        this.mustRevalidate = mustRevalidate;
        this.maxAgeSeconds = maxAgeSeconds;
        this.staleWhileRevalidateSeconds = staleWhileRevalidateSeconds;
        this.isPrivate = isPrivate;
        this.isPublic = isPublic;
    }

    /**
     * Returns an empty {@link CacheControl} instance with no directives set.
     *
     * @return empty cache control
     */
    public static CacheControl empty() {
        return EMPTY;
    }

    /**
     * Parses {@code Cache-Control} directives from a list of HTTP headers.
     *
     * @param headers the list of headers, or {@code null}
     * @return parsed {@link CacheControl}
     */
    public static CacheControl parse(List<Header> headers) {
        if (headers == null || headers.isEmpty()) {
            return EMPTY;
        }
        boolean noCache = false;
        boolean noStore = false;
        boolean mustRevalidate = false;
        Long maxAge = null;
        Long staleWhileRevalidate = null;
        boolean isPrivate = false;
        boolean isPublic = false;
        boolean found = false;

        for (Header h : headers) {
            if (h != null && h.name() != null && h.name().equalsIgnoreCase("Cache-Control")) {
                String val = h.value();
                if (val != null) {
                    found = true;
                    String[] tokens = val.split(",");
                    for (String token : tokens) {
                        String t = token.trim();
                        if (t.isEmpty()) {
                            continue;
                        }
                        int eq = t.indexOf('=');
                        String name = (eq >= 0 ? t.substring(0, eq) : t).trim().toLowerCase(Locale.ROOT);
                        String param = eq >= 0 ? t.substring(eq + 1).trim() : null;

                        switch (name) {
                            case "no-cache" -> noCache = true;
                            case "no-store" -> noStore = true;
                            case "must-revalidate" -> mustRevalidate = true;
                            case "private" -> isPrivate = true;
                            case "public" -> isPublic = true;
                            case "max-age" -> {
                                if (param != null) {
                                    try {
                                        long sec = Long.parseLong(stripQuotes(param));
                                        if (sec >= 0) {
                                            maxAge = sec;
                                        }
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                            }
                            case "stale-while-revalidate" -> {
                                if (param != null) {
                                    try {
                                        long sec = Long.parseLong(stripQuotes(param));
                                        if (sec >= 0) {
                                            staleWhileRevalidate = sec;
                                        }
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                            }
                            default -> {
                            }
                        }
                    }
                }
            }
        }

        if (!found) {
            return EMPTY;
        }
        return new CacheControl(noCache, noStore, mustRevalidate, maxAge, staleWhileRevalidate, isPrivate, isPublic);
    }

    /**
     * Parses {@code Cache-Control} directives from a raw header string.
     *
     * @param rawValue raw header value, or {@code null}
     * @return parsed {@link CacheControl}
     */
    public static CacheControl parse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return EMPTY;
        }
        return parse(List.of(new Header("Cache-Control", rawValue)));
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /**
     * Returns whether {@code no-cache} directive is active.
     *
     * @return {@code true} if no-cache is present
     */
    public boolean noCache() {
        return noCache;
    }

    /**
     * Returns whether {@code no-store} directive is active.
     *
     * @return {@code true} if no-store is present
     */
    public boolean noStore() {
        return noStore;
    }

    /**
     * Returns whether {@code must-revalidate} directive is active.
     *
     * @return {@code true} if must-revalidate is present
     */
    public boolean mustRevalidate() {
        return mustRevalidate;
    }

    /**
     * Returns the {@code max-age} in seconds, or {@code null} if not specified.
     *
     * @return max-age in seconds, or {@code null}
     */
    public Long maxAgeSeconds() {
        return maxAgeSeconds;
    }

    /**
     * Returns the {@code stale-while-revalidate} in seconds, or {@code null} if not specified.
     *
     * @return stale-while-revalidate in seconds, or {@code null}
     */
    public Long staleWhileRevalidateSeconds() {
        return staleWhileRevalidateSeconds;
    }

    /**
     * Returns whether {@code private} directive is active.
     *
     * @return {@code true} if private is present
     */
    public boolean isPrivate() {
        return isPrivate;
    }

    /**
     * Returns whether {@code public} directive is active.
     *
     * @return {@code true} if public is present
     */
    public boolean isPublic() {
        return isPublic;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (noCache) sb.append("no-cache, ");
        if (noStore) sb.append("no-store, ");
        if (mustRevalidate) sb.append("must-revalidate, ");
        if (maxAgeSeconds != null) sb.append("max-age=").append(maxAgeSeconds).append(", ");
        if (staleWhileRevalidateSeconds != null) sb.append("stale-while-revalidate=").append(staleWhileRevalidateSeconds).append(", ");
        if (isPrivate) sb.append("private, ");
        if (isPublic) sb.append("public, ");
        if (sb.length() >= 2) {
            sb.setLength(sb.length() - 2);
        }
        return "CacheControl[" + sb + "]";
    }
}
