package ru.jrestly.http;

/**
 * Internal name/value pair for query-string and form-body parameters.
 * (The public {@link Header} record stays reserved for HTTP headers.)
 *
 * @param name the parameter name, as sent in the query string or the form body
 * @param value the parameter value, as sent in the query string or the form body
 */
public record Param(String name, String value) {
}
