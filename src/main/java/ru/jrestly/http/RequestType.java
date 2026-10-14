package ru.jrestly.http;

/**
 * The payload content types a controller method can declare through the
 * verb annotation's {@code requestType} attribute
 * ({@code @Post}, {@code @Put}, {@code @Patch}, {@code @Delete});
 * {@code @Get} is fixed to {@link #APPLICATION_JSON}.
 *
 * <p>Each constant carries the canonical MIME type the template sets as
 * the request {@code Content-Type} (see {@link #mime()}); for
 * {@link #MULTIPART_FORM_DATA} the per-request boundary is appended to it.
 */
public enum RequestType {
    /** JSON payload — the {@code @RequestBody} argument serialized by the module's JSON codec; the default of every verb annotation. */
    APPLICATION_JSON("application/json"),

    /** The controller's parameters encoded as {@code name=value} pairs in the request body; no query parameters are sent; requires at least one parameter. */
    APPLICATION_FORM_URLENCODED("application/x-www-form-urlencoded"),

    /** {@code @MultipartFormFile} parameters become individual multipart parts; no query parameters are sent; the boundary is generated per request; requires at least one parameter. */
    MULTIPART_FORM_DATA("multipart/form-data");

    private final String mime;

    RequestType(String mime) {
        this.mime = mime;
    }

    /**
     * The canonical MIME type of this content type.
     *
     * @return the MIME type string, e.g. {@code "application/json"}
     */
    public String mime() {
        return mime;
    }
}
