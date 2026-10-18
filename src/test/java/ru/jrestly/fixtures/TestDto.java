package ru.jrestly.fixtures;

/**
 * The canonical test DTO record (id, name, description): JSON
 * round-trips, {@code @RequestBody} payloads and typed response binding
 * across the core suites.
 */
public record TestDto(
        Long id,
        String name,
        String description
) {}
