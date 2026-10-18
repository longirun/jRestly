/**
 * Circuit-breaker and client rate-limiter SPI (ADR-0043): the
 * {@link ru.jrestly.resilience.CircuitBreaker} and
 * {@link ru.jrestly.resilience.RateLimiter} contracts with their immutable
 * configuration, snapshots, registries, and compact in-memory defaults.
 *
 * <p>These are local, per-client guards — deliberately not a distributed
 * resilience suite (ADR-0003).
 */
package ru.jrestly.resilience;
