/**
 * XML payload codec for jRestly (ADR-0060).
 *
 * <p>Binds {@code application/xml} / {@code text/xml} / {@code *+xml} payloads
 * through the core {@link ru.jrestly.codec.PayloadCodec} SPI, backed by a Jackson
 * {@code XmlMapper}. The codec lives outside the zero-dependency core: register it
 * explicitly with {@code JRestlyClient.builder().codec(...)} and the declarative
 * layer keeps working unchanged for either JSON or XML.
 */
package ru.jrestly.codec.jacksonxml;
