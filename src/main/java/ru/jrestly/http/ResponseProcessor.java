package ru.jrestly.http;

import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.tracing.TraceIdExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Handles HTTP response post-processing, decoding, error routing, and streaming lifecycle (ADR-0094).
 *
 * <p>Coordinates:
 * <ul>
 *   <li>Response duration calculation and diagnostic logging (via {@link HttpDiagnosticLogger})</li>
 *   <li>Response headers population (holder and ThreadLocal captor {@link ResponseHeaders#CURRENT_CAPTOR})</li>
 *   <li>Auth details callback execution</li>
 *   <li>Streaming responses ({@link InputStream}, {@code Stream<String>} lines, {@code Stream<ServerSentEvent>})
 *       with trailer wrapping and optional empty semantics</li>
 *   <li>Status code expectation validation and {@code @OnError} dispatch (via {@link HttpErrorHandler})</li>
 *   <li>Buffered payload decoding into raw types, String, byte[], or DTOs via resolved {@link PayloadCodec}</li>
 * </ul>
 */
final class ResponseProcessor {

    record ResponseConfig(
            Type returnType,
            boolean optional,
            ResponseHeaders responseHeadersHolder,
            Consumer<List<Header>> authDetailsConsumer,
            OnErrorParser onErrorParser,
            List<Integer> expectStatuses,
            boolean conditional,
            Function<List<Header>, PayloadCodec> codecResolver,
            System.Logger logger,
            boolean sanitizeLogs
    ) {
        ResponseConfig(
                Type returnType,
                boolean optional,
                ResponseHeaders responseHeadersHolder,
                Consumer<List<Header>> authDetailsConsumer,
                OnErrorParser onErrorParser,
                List<Integer> expectStatuses,
                boolean conditional,
                Function<List<Header>, PayloadCodec> codecResolver,
                System.Logger logger
        ) {
            this(returnType, optional, responseHeadersHolder, authDetailsConsumer, onErrorParser,
                    expectStatuses, conditional, codecResolver, logger,
                    HttpDiagnosticLogger.isSanitizingEnabled());
        }

        ResponseConfig {
            logger = logger != null ? logger : System.getLogger(ResponseProcessor.class.getName());
        }
    }

    private final ResponseConfig config;

    ResponseProcessor(ResponseConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @SuppressWarnings("unchecked")
    <T> T process(WireResponse response, long startNanos) throws IOException {
        return process(null, response, startNanos);
    }

    @SuppressWarnings("unchecked")
    <T> T process(WireRequest request, WireResponse response, long startNanos) throws IOException {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        List<Header> responseHeaders = response.headers();
        int statusCode = response.statusCode();
        Charset payloadCharset = ResponseBody.resolveCharset(responseHeaders);

        ResponseHeaders capturedHeaderHolder = captureHeadersAndNotify(response, responseHeaders, statusCode, startNanos);

        String traceId = TraceIdExtractor.extract(response);
        if (traceId == null && request != null) {
            traceId = TraceIdExtractor.extract(request);
        }
        HttpDiagnosticLogger.logResponse(config.logger(), response, elapsedMs, traceId, config.sanitizeLogs());

        InputStream stream = response.stream();
        if (stream != null) {
            return (T) processStream(response, stream, responseHeaders, statusCode, payloadCharset, capturedHeaderHolder);
        }

        return (T) processBuffered(response, responseHeaders, statusCode, payloadCharset);
    }

    private ResponseHeaders captureHeadersAndNotify(
            WireResponse response, List<Header> responseHeaders, int statusCode, long startNanos) {
        Consumer<ResponseHeaders> captor = ResponseHeaders.CURRENT_CAPTOR.get();
        ResponseHeaders capturedHeaderHolder = null;
        if (config.responseHeadersHolder() != null || captor != null) {
            Duration callDuration = Duration.ofNanos(System.nanoTime() - startNanos);
            if (config.responseHeadersHolder() != null) {
                config.responseHeadersHolder().populate(
                        statusCode, responseHeaders, callDuration, response.version(), response.trailers());
            }
            if (captor != null) {
                capturedHeaderHolder = new ResponseHeaders(
                        statusCode, responseHeaders, callDuration, response.version(), response.trailers());
                captor.accept(capturedHeaderHolder);
            }
        }

        if (config.authDetailsConsumer() != null) {
            config.authDetailsConsumer().accept(responseHeaders);
        }

        return capturedHeaderHolder;
    }

    @SuppressWarnings("unchecked")
    private Object processStream(
            WireResponse response,
            InputStream stream,
            List<Header> responseHeaders,
            int statusCode,
            Charset payloadCharset,
            ResponseHeaders capturedHeaderHolder) throws IOException {

        // ADR-0059: Terminal streaming hook for late-arriving trailers
        stream = ResponseBody.wrapForTrailers(
                stream, response.trailersSupplier(), config.responseHeadersHolder(), capturedHeaderHolder);

        if (config.onErrorParser() != null && config.onErrorParser().canParse(statusCode)) {
            String errorPayload = ResponseBody.consumePayload(stream, payloadCharset);
            handleOnError(statusCode, errorPayload, responseHeaders, payloadCharset);
        }

        if (!isExpectedStatus(statusCode)) {
            String rawPayload = ResponseBody.consumePayload(stream, payloadCharset);
            throw createUnexpectedStatusException(statusCode, rawPayload == null ? "" : rawPayload, responseHeaders);
        }

        // ADR-0016 Aspect 1 & 5: Stream<String> returns line stream;
        // Stream<ServerSentEvent> returns parsed event stream
        if (ResponseBody.isStreamType(config.returnType(), String.class)) {
            if (config.logger().isLoggable(System.Logger.Level.INFO)) {
                config.logger().log(System.Logger.Level.INFO, ResponseBody.streamingPayloadLine("streaming lines", responseHeaders));
            }
            Stream<String> lineStream = ResponseBody.lineStream(stream, payloadCharset, config.logger());
            if (config.optional()) {
                if (ResponseBody.isMissingResource(statusCode)) {
                    lineStream.close();
                    return Optional.empty();
                }
                return Optional.of(lineStream);
            }
            return lineStream;
        }

        if (ResponseBody.isStreamType(config.returnType(), ServerSentEvent.class)) {
            if (config.logger().isLoggable(System.Logger.Level.INFO)) {
                config.logger().log(System.Logger.Level.INFO, ResponseBody.streamingPayloadLine("streaming events", responseHeaders));
            }
            Stream<ServerSentEvent> eventStream = ResponseBody.eventStream(stream, payloadCharset, config.logger());
            if (config.optional()) {
                if (ResponseBody.isMissingResource(statusCode)) {
                    eventStream.close();
                    return Optional.empty();
                }
                return Optional.of(eventStream);
            }
            return eventStream;
        }

        // ADR-0097: Typed Server-Sent Events decoding
        if (ResponseBody.isTypedEventStream(config.returnType())) {
            if (config.logger().isLoggable(System.Logger.Level.INFO)) {
                config.logger().log(System.Logger.Level.INFO, ResponseBody.streamingPayloadLine("streaming typed events", responseHeaders));
            }
            if (config.optional() && ResponseBody.isMissingResource(statusCode)) {
                ResponseBody.closeQuietly(stream, config.logger());
                return Optional.empty();
            }
            Type payloadType = ResponseBody.extractTypedEventType(config.returnType());
            PayloadCodec codec = resolveCodec(responseHeaders);
            if (codec == null) {
                throw new IllegalStateException("PayloadCodec is required to decode TypedServerSentEvent stream, but none was configured");
            }
            Stream<?> eventStream = ResponseBody.typedEventStream(stream, payloadCharset, codec, payloadType, config.logger());
            if (config.optional()) {
                return Optional.of(eventStream);
            }
            return eventStream;
        }

        // ADR-0098: Direct typed data streaming (Stream<T> for SSE and NDJSON)
        if (ResponseBody.isDataStream(config.returnType())) {
            if (config.logger().isLoggable(System.Logger.Level.INFO)) {
                config.logger().log(System.Logger.Level.INFO, ResponseBody.streamingPayloadLine("streaming data events", responseHeaders));
            }
            if (config.optional() && ResponseBody.isMissingResource(statusCode)) {
                ResponseBody.closeQuietly(stream, config.logger());
                return Optional.empty();
            }
            Type payloadType = ResponseBody.extractStreamElementType(config.returnType());
            PayloadCodec codec = resolveCodec(responseHeaders);
            if (codec == null) {
                throw new IllegalStateException("PayloadCodec is required to decode " + config.returnType().getTypeName() + " stream, but none was configured");
            }
            String contentType = ResponseBody.contentTypeOf(responseHeaders);
            Stream<?> dataStream = ResponseBody.dataStream(stream, payloadCharset, codec, payloadType, contentType, config.logger());
            if (config.optional()) {
                return Optional.of(dataStream);
            }
            return dataStream;
        }

        // ADR-0015 Aspect 5: raw stream
        if (config.logger().isLoggable(System.Logger.Level.INFO)) {
            config.logger().log(System.Logger.Level.INFO, ResponseBody.streamingPayloadLine("streaming", responseHeaders));
        }
        if (config.optional()) {
            if (ResponseBody.isMissingResource(statusCode)) {
                ResponseBody.closeQuietly(stream, config.logger());
                return Optional.empty();
            }
            if (InputStream.class.equals(config.returnType())) {
                return Optional.of(stream);
            }
        }
        return stream;
    }

    private Object processBuffered(
            WireResponse response,
            List<Header> responseHeaders,
            int statusCode,
            Charset payloadCharset) {

        byte[] bytes = response.body();

        if (config.onErrorParser() != null && config.onErrorParser().canParse(statusCode)) {
            String errorPayload = (bytes != null && bytes.length > 0) ? new String(bytes, payloadCharset) : null;
            handleOnError(statusCode, errorPayload, responseHeaders, payloadCharset);
        }

        if (!isExpectedStatus(statusCode)) {
            String errorPayload = (bytes != null && bytes.length > 0) ? new String(bytes, payloadCharset) : "";
            throw createUnexpectedStatusException(statusCode, errorPayload, responseHeaders);
        }

        if (config.optional()) {
            return ResponseBody.bufferedOptional(
                    statusCode,
                    bytes,
                    config.returnType(),
                    payloadCharset,
                    ResponseBody.contentTypeOf(responseHeaders),
                    () -> resolveCodec(responseHeaders),
                    config.logger());
        }
        return ResponseBody.bufferedRaw(
                bytes,
                config.returnType(),
                payloadCharset,
                ResponseBody.contentTypeOf(responseHeaders),
                () -> resolveCodec(responseHeaders),
                config.logger());
    }

    private boolean isExpectedStatus(int statusCode) {
        return HttpErrorHandler.isExpectedStatus(
                config.expectStatuses(), config.conditional(), config.optional(), statusCode);
    }

    private void handleOnError(
            int statusCode, String payload, List<Header> responseHeaders, Charset payloadCharset) {
        HttpErrorHandler.handleOnError(
                config.onErrorParser(),
                () -> resolveCodec(responseHeaders),
                statusCode,
                payload,
                responseHeaders,
                payloadCharset);
    }

    private UnexpectedStatusException createUnexpectedStatusException(
            int statusCode, String rawBody, List<Header> responseHeaders) {
        return HttpErrorHandler.createUnexpectedStatusException(
                statusCode, rawBody, responseHeaders, resolveCodecOrNull(responseHeaders));
    }

    private PayloadCodec resolveCodec(List<Header> responseHeaders) {
        if (config.codecResolver() == null) {
            return null;
        }
        return config.codecResolver().apply(responseHeaders);
    }

    private PayloadCodec resolveCodecOrNull(List<Header> responseHeaders) {
        try {
            return resolveCodec(responseHeaders);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
