package ru.jrestly.http;

import java.util.List;

/**
 * Internal wire model: the engine's view of a response — status, headers
 * and the raw payload bytes.
 */
public record WireResponse(int statusCode, List<Header> headers, byte[] body) {
}
