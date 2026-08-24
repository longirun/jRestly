package ru.jrestly.http;

import java.net.URI;
import java.util.List;

/**
 * Internal wire model: everything needed to place a request on the wire,
 * decoupled from both the template layer and the HTTP engine.
 * <p>
 * For multipart requests {@code contentType} carries the boundary parameter
 * and {@code body} is the pre-rendered multipart payload; {@code parts} is
 * kept alongside solely for request logging.
 */
public record WireRequest(
        HttpMethod method,
        URI uri,
        List<Header> headers,
        String contentType,
        byte[] body,
        List<MultipartPart> parts
) {
}
