/**
 * Client metrics and telemetry SPI (ADR-0036): the
 * {@link ru.jrestly.metrics.ClientMetricsListener} lifecycle contract
 * (call start/complete, per-attempt, retry), the immutable
 * {@link ru.jrestly.metrics.CallMetrics} snapshot, and the
 * {@link ru.jrestly.metrics.CompositeClientMetricsListener} fan-out.
 *
 * <p>Listeners are driven for both the synchronous and asynchronous paths.
 */
package ru.jrestly.metrics;
