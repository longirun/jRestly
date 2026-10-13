package ru.jrestly.http;

import java.net.URI;
import java.util.List;

/**
 * Internal wire model: everything needed to place a request on the wire,
 * decoupled from both the template layer and the HTTP engine.
 * <p>
 * For multipart requests {@code contentType} carries the boundary parameter
 * and {@code body} is unused; the payload is rendered per send from
 * {@code parts} + {@code boundary} as a streaming publisher, so a redirect
 * re-send gets a fresh body.
 *
 * @param method the HTTP method for this hop; a redirect hop may replace it (GET unless the redirect status preserves the method)
 * @param uri the target URI for this hop; a redirect hop resolves the {@code Location} header against it
 * @param headers the headers to send with this hop, or {@code null}; cross-origin redirect hops drop the default auth headers
 * @param contentType the {@code Content-Type} header value, or {@code null}; for multipart requests it carries the boundary parameter; dropped on redirect hops that do not preserve the payload
 * @param body the raw payload bytes, or {@code null} for a body-less request; unused for multipart requests
 * @param parts the multipart parts, or {@code null} for non-multipart requests; rendered per send as a streaming publisher
 * @param boundary the multipart boundary used to render {@code parts}, or {@code null} for non-multipart requests
 */
public record WireRequest(
        HttpMethod method,
        URI uri,
        List<Header> headers,
        String contentType,
        byte[] body,
        List<MultipartPart> parts,
        String boundary
) {
}
