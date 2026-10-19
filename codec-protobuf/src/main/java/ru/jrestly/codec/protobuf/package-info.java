/**
 * Protocol Buffers payload codec for jRestly (ADR-0074).
 *
 * <p>Binds {@code application/x-protobuf} / {@code application/protobuf} /
 * {@code *+protobuf} payloads through the core
 * {@link ru.jrestly.codec.PayloadCodec} SPI, backed by
 * {@code com.google.protobuf.Message}/{@code Parser} from
 * {@code com.google.protobuf:protobuf-java}. The codec lives outside the
 * zero-dependency core: register it explicitly with
 * {@code JRestlyClient.builder().codec(...)} and the declarative layer keeps
 * working unchanged for either JSON or Protocol Buffers.
 */
package ru.jrestly.codec.protobuf;
