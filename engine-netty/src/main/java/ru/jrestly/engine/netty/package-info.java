/**
 * Netty transport engine for jRestly (ADR-0070).
 *
 * <p>Plugs jRestly onto Netty through the core {@link ru.jrestly.engine.HttpEngine}
 * SPI: the declarative layer (interceptors, retry, compression, metrics, DTO mapping) runs
 * on top of Netty's high-performance non-blocking transport. The engine is auto-discovered via
 * {@code META-INF/services/ru.jrestly.engine.HttpEngineProvider}; the zero-dependency
 * JDK engine stays the default when this module is absent.
 */
package ru.jrestly.engine.netty;
