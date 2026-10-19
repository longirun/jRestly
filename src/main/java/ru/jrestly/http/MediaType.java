package ru.jrestly.http;

/**
 * Standard HTTP Media Type constants for use with {@link ru.jrestly.annotation.Accept},
 * {@link ru.jrestly.annotation.ContentType}, and request/response header definitions.
 */
public final class MediaType {

    /** The {@code application/json} media type. */
    public static final String APPLICATION_JSON = "application/json";

    /** The {@code application/xml} media type. */
    public static final String APPLICATION_XML = "application/xml";

    /** The {@code application/octet-stream} binary media type. */
    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

    /** The {@code application/x-www-form-urlencoded} media type. */
    public static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";

    /** The {@code multipart/form-data} media type. */
    public static final String MULTIPART_FORM_DATA = "multipart/form-data";

    /** The {@code text/plain} media type. */
    public static final String TEXT_PLAIN = "text/plain";

    /** The {@code text/html} media type. */
    public static final String TEXT_HTML = "text/html";

    /** The {@code text/xml} media type. */
    public static final String TEXT_XML = "text/xml";

    /** The {@code application/yaml} media type (RFC 9512). */
    public static final String APPLICATION_YAML = "application/yaml";

    /** The {@code text/yaml} media type (RFC 9512). */
    public static final String TEXT_YAML = "text/yaml";

    /** The {@code text/event-stream} Server-Sent Events media type. */
    public static final String TEXT_EVENT_STREAM = "text/event-stream";

    /** The {@code application/x-ndjson} newline-delimited JSON media type. */
    public static final String APPLICATION_NDJSON = "application/x-ndjson";

    /** The wildcard media type matching any MIME type. */
    public static final String ALL = "*/*";

    private MediaType() {
        // utility class
    }
}
