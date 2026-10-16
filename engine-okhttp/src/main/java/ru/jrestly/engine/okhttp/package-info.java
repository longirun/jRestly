/**
 * OkHttp transport engine for jRestly (ADR-0058).
 *
 * <p>Plugs jRestly onto Square OkHttp through the core {@link ru.jrestly.engine.HttpEngine}
 * SPI: the declarative layer (interceptors, retry, compression, metrics, DTO mapping) runs
 * on top of the OkHttp transport. The engine is auto-discovered via
 * {@code META-INF/services/ru.jrestly.engine.HttpEngineProvider}; the zero-dependency
 * JDK engine stays the default when this module is absent.
 */
package ru.jrestly.engine.okhttp;
