package ru.jrestly.fixtures;

/**
 * DTO whose fields may be null: fixture for the JSON null-field
 * serialization tests.
 */
public record NullableFieldDto(
        Long id,
        String name
) {}
