package ru.jrestly.http;

/**
 * Internal name/value pair for query-string and form-body parameters.
 * (The public {@link Header} record stays reserved for HTTP headers.)
 */
public record Param(String name, String value) {
}
