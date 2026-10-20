/**
 * The internal request pipeline: assembly ({@code HttpTemplateBuilder},
 * {@code UrlComposer}, {@code HeaderComposer}, {@code BodyComposer},
 * {@code ResilienceComposer}, {@code MethodMetadata}, {@code MethodMetadataParser}), execution
 * ({@code HttpTemplate} — redirects, {@code RetryExecutor},
 * streaming, {@code ResponseDecompressor}, {@code HttpErrorHandler}, {@code HttpDiagnosticLogger}, cache dispatch, problem-detail resolution), the raw
 * {@code WireRequest}/{@code WireResponse} model, response metadata
 * ({@code ResponseHeaders}), SSE/line streaming, multipart and progress
 * publishing, and the typed exception hierarchy.
 *
 * <p>Everything here is an implementation detail: {@code HttpTemplate} is
 * package-private (ADR-0048) and package-private seams exist only so unit tests
 * can drive them without a network. Only the public SPI types (interceptors,
 * listeners, codecs, engines) are meant to be used directly.
 */
package ru.jrestly.http;
