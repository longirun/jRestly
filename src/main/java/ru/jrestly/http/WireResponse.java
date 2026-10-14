package ru.jrestly.http;

import java.util.List;

/**
 * Internal wire model: the engine's view of a response — status, headers
 * and the raw payload bytes.
 *
 * @param statusCode the HTTP status code; classified against the {@code @OnError} statuses, the {@code @ExpectStatus} list, and the redirect statuses (301/302/303/307/308)
 * @param headers the response headers, one {@link Header} per entry of the JDK header map; consumed for the redirect {@code Location} lookup, the {@code @SetAuthDetails} capture, and as the {@link HandledException} details
 * @param body the raw payload bytes, or {@code null} for a body-less response; decoded into the method's return type by the module's codec, or into the {@code @OnError} error class
 */
public record WireResponse(int statusCode, List<Header> headers, byte[] body) {
}
