package ru.jrestly.http;

/**
 * Immutable name/value pair used for request headers, response headers
 * and captured auth details across the jRestly API.
 *
 * @param name the header name
 * @param value the header value
 */
public record Header(String name, String value) {
}
