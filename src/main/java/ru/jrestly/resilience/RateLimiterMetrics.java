package ru.jrestly.resilience;

/**
 * Snapshot of metrics for a {@link RateLimiter}.
 *
 * @param availablePermissions number of available permissions/tokens at the time of the snapshot
 * @param numberOfWaitingThreads approximate number of threads waiting for permissions
 */
public record RateLimiterMetrics(
        int availablePermissions,
        int numberOfWaitingThreads
) {}
