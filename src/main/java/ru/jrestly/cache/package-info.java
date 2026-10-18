/**
 * Transparent RFC 9111 HTTP cache SPI and its in-memory store (ADR-0063): the
 * {@link ru.jrestly.cache.HttpCache} contract with {@code get}/{@code put}/
 * {@code update}/{@code invalidate}, the immutable
 * {@link ru.jrestly.cache.CachedResponse} entry, the {@code Cache-Control}
 * directive parser, and the LRU {@link ru.jrestly.cache.InMemoryHttpCache}
 * default.
 *
 * <p>The pipeline consults the cache only for safe methods and revalidates
 * stale entries through conditional requests, synthesizing a {@code 200 OK}
 * from a {@code 304 Not Modified}.
 */
package ru.jrestly.cache;
