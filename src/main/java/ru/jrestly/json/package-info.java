/**
 * Built-in JSON codec SPI (ADR-0007, ADR-0045): the
 * {@link ru.jrestly.json.JsonCodec} contract, its zero-dependency
 * {@link ru.jrestly.json.NoOpJsonCodec} fallback, the reflective
 * {@link ru.jrestly.json.JacksonCodec}/{@code GsonCodec}/{@code MoshiCodec}
 * adapters, and the {@link ru.jrestly.json.JsonCodecResolver} that picks the
 * first available backend at runtime.
 *
 * <p>JSON support is {@code compileOnly}: the core jar stays dependency-free.
 */
package ru.jrestly.json;
