package ru.jrestly.benchmark.dto;

import java.util.List;

/**
 * Common data model used across comparative benchmarks.
 *
 * @param id        user identifier
 * @param name      full user name
 * @param email     user email address
 * @param active    account status flag
 * @param tags      list of role tags
 * @param createdAt ISO-8601 creation timestamp
 */
public record UserDto(
        long id,
        String name,
        String email,
        boolean active,
        List<String> tags,
        String createdAt
) {}
