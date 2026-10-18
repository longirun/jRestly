/**
 * Request-body compression and {@code Content-Encoding} SPI (ADR-0041, ADR-0057):
 * the {@link ru.jrestly.compression.ContentCompressor} contract with its
 * built-in gzip/deflate implementations, the
 * {@link ru.jrestly.compression.CompressionAlgorithm} selector, and the
 * registry used to resolve a compressor by algorithm.
 *
 * <p>Response decompression is symmetrical and preserves streaming paths.
 */
package ru.jrestly.compression;
