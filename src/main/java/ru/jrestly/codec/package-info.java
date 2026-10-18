/**
 * Media-type-aware payload codec SPI (ADR-0060): the
 * {@link ru.jrestly.codec.PayloadCodec} contract, the
 * {@link ru.jrestly.codec.CodecRegistry} that selects a codec by the effective
 * {@code Content-Type}, the {@link ru.jrestly.codec.JsonCodecAdapter} bridge to
 * the built-in JSON SPI, and the ServiceLoader-based
 * {@link ru.jrestly.codec.PayloadCodecProvider} auto-discovery.
 *
 * <p>JSON remains the default; a custom format is one interface away, and the
 * adapters ship as optional submodules (for example, the Jackson XML codec).
 */
package ru.jrestly.codec;
