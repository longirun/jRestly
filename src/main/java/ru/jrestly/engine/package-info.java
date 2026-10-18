/**
 * HTTP engine SPI and the JDK transport (ADR-0052, ADR-0058): the
 * {@link ru.jrestly.engine.HttpEngine} contract, its per-call
 * {@link ru.jrestly.engine.HttpEngineOptions}, the ServiceLoader
 * {@link ru.jrestly.engine.HttpEngineProvider} with priority-based resolution,
 * and the zero-dependency {@link ru.jrestly.engine.JdkHttpEngine} default.
 *
 * <p>External transports (OkHttp, Apache HC5) ship as optional submodules and
 * are picked up automatically when present.
 */
package ru.jrestly.engine;
