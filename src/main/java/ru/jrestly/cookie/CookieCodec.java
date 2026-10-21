package ru.jrestly.cookie;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpCookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Utility for formatting and parsing {@link HttpCookie} objects according to RFC 6265 standards (ADR-0039).
 * <p>
 * Provides stream and string-based serialization/deserialization for cookie persistence.
 */
public final class CookieCodec {

    private CookieCodec() {
    }

    /**
     * Formats an {@link HttpCookie} into an RFC 6265 compliant {@code Set-Cookie} string.
     *
     * @param cookie the cookie to format, not {@code null}
     * @return the RFC 6265 formatted string
     */
    public static String format(HttpCookie cookie) {
        Objects.requireNonNull(cookie, "cookie must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append(cookie.getName()).append('=').append(cookie.getValue() != null ? cookie.getValue() : "");

        String domain = cookie.getDomain();
        if (domain != null && !domain.isBlank()) {
            sb.append("; Domain=").append(domain);
        }

        String path = cookie.getPath();
        if (path != null && !path.isBlank()) {
            sb.append("; Path=").append(path);
        }

        long maxAge = cookie.getMaxAge();
        if (maxAge >= 0) {
            sb.append("; Max-Age=").append(maxAge);
        }

        if (cookie.getSecure()) {
            sb.append("; Secure");
        }

        if (cookie.isHttpOnly()) {
            sb.append("; HttpOnly");
        }

        if (cookie.getVersion() > 0) {
            sb.append("; Version=").append(cookie.getVersion());
        }

        return sb.toString();
    }

    /**
     * Parses a single RFC 6265 line into a list of valid, non-expired {@link HttpCookie} instances.
     * Ignores blank lines and comments starting with {@code #}.
     *
     * @param line the line to parse
     * @return list of parsed cookies, empty if line is comment, blank, or invalid
     */
    public static List<HttpCookie> parse(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("#")) {
            return List.of();
        }
        try {
            List<HttpCookie> parsed = HttpCookie.parse(trimmed);
            List<HttpCookie> valid = new ArrayList<>(parsed.size());
            for (HttpCookie c : parsed) {
                if (!c.hasExpired()) {
                    valid.add(c);
                }
            }
            return Collections.unmodifiableList(valid);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    /**
     * Writes a collection of cookies to the specified writer, skipping expired cookies.
     * Includes a header comment with generation timestamp.
     *
     * @param writer the destination writer, not {@code null}
     * @param cookies the cookies to write, not {@code null}
     * @throws IOException if an I/O error occurs
     */
    public static void write(Writer writer, Collection<HttpCookie> cookies) throws IOException {
        Objects.requireNonNull(writer, "writer must not be null");
        Objects.requireNonNull(cookies, "cookies must not be null");

        writer.write("# jRestly Persistent Cookie Storage (RFC 6265 format)\n");
        writer.write("# Saved: " + Instant.now() + "\n");

        for (HttpCookie cookie : cookies) {
            if (cookie != null && !cookie.hasExpired()) {
                writer.write(format(cookie));
                writer.write('\n');
            }
        }
        writer.flush();
    }

    /**
     * Reads all non-expired cookies from the specified reader.
     * Comments and blank lines are ignored.
     *
     * @param reader the source reader, not {@code null}
     * @return list of loaded non-expired cookies
     * @throws IOException if an I/O error occurs
     */
    public static List<HttpCookie> read(Reader reader) throws IOException {
        Objects.requireNonNull(reader, "reader must not be null");
        BufferedReader bufferedReader = reader instanceof BufferedReader br ? br : new BufferedReader(reader);
        List<HttpCookie> result = new ArrayList<>();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            List<HttpCookie> cookies = parse(line);
            result.addAll(cookies);
        }
        return Collections.unmodifiableList(result);
    }
}
