package ru.jrestly.http;

import ru.jrestly.tracing.TraceIdExtractor;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.metrics.ClientMetricsListener;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Executes requests under a {@link RetryPolicy} with configurable backoff,
 * jitter, Retry-After header support, status classification, idempotent guards,
 * and payload decode probe (ADR-0008, ADR-0011, ADR-0015, ADR-0040, ADR-0091).
 *
 * <p>Extracted from {@link HttpTemplate} as part of god-class decomposition (ADR-0081).
 */
final class RetryExecutor {

    @FunctionalInterface
    interface SyncSender {
        WireResponse send(WireRequest request) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface AsyncSender {
        CompletableFuture<WireResponse> send(WireRequest request);
    }

    record RetryConfig(
            RetryPolicy retryPolicy,
            HttpMethod httpMethod,
            System.Logger logger,
            ClientMetricsListener metricsListener,
            OnErrorParser onErrorParser,
            List<Integer> expectStatuses,
            boolean conditional,
            boolean optional,
            Type returnType,
            Function<List<Header>, PayloadCodec> codecResolver,
            Executor executor
    ) {
        RetryConfig {
            if (retryPolicy == null) {
                retryPolicy = RetryPolicy.off();
            }
        }

        boolean isExpectedStatus(int statusCode) {
            return HttpErrorHandler.isExpectedStatus(expectStatuses, conditional, optional, statusCode);
        }
    }

    private final RetryConfig config;

    RetryExecutor(RetryConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    WireResponse execute(
            WireRequest request,
            RequestContext requestContext,
            int[] attemptsHolder,
            int attemptOffset,
            SyncSender sender
    ) throws IOException, InterruptedException {
        RetryPolicy retryPolicy = config.retryPolicy();
        if (!retryPolicy.isEnabled()) {
            long attemptStart = System.nanoTime();
            WireResponse response = null;
            Throwable failure = null;
            try {
                response = sender.send(request);
                return response;
            } catch (Throwable t) {
                failure = t;
                throw t;
            } finally {
                MetricsNotifier.attemptComplete(config.metricsListener(), config.logger(), requestContext, request, 1 + attemptOffset, response, failure, attemptStart);
            }
        }

        int maxAttempts = retryPolicy.maxAttempts();
        long lastDelayMillis = 0L;

        for (int attempt = 1; ; attempt++) {
            if (attemptsHolder != null) {
                attemptsHolder[0] = attempt;
            }
            boolean last = attempt == maxAttempts;

            long attemptStart = System.nanoTime();
            WireResponse response;
            String failure;
            Throwable failureThrowable = null;
            try {
                response = sender.send(request);
                failure = "HTTP " + response.statusCode();
                if (!config.isExpectedStatus(response.statusCode())) {
                    failureThrowable = createUnexpectedStatusException(response.statusCode(), ResponseBody.extractText(response), response.headers());
                }
            } catch (InterruptedException e) {
                // the caller's thread was interrupted — fail fast, never retried
                failureThrowable = e;
                MetricsNotifier.attemptComplete(config.metricsListener(), config.logger(), requestContext, request, attempt + attemptOffset, null, failureThrowable, attemptStart);
                throw e;
            } catch (IOException e) {
                failureThrowable = e;
                MetricsNotifier.attemptComplete(config.metricsListener(), config.logger(), requestContext, request, attempt + attemptOffset, null, failureThrowable, attemptStart);
                if (last || !retryPolicy.isRetryTransport()) {
                    throw e;
                }
                response = null;
                failure = e.getClass().getSimpleName();
            }

            if (response != null) {
                MetricsNotifier.attemptComplete(config.metricsListener(), config.logger(), requestContext, request, attempt + attemptOffset, response, failureThrowable, attemptStart);
            }

            if (response != null && (last || !isRetryableStatus(response))) {
                if (!last && retryPolicy.isRetryDecodeFailures()) {
                    try {
                        probePayloadDecode(response);
                    } catch (RuntimeException e) {
                        lastDelayMillis = scheduleRetry(attempt, maxAttempts, "decode (" + e.getClass().getSimpleName() + ")", response, requestContext, request, e, lastDelayMillis, attemptOffset);
                        continue;
                    }
                }
                return response;
            }

            lastDelayMillis = scheduleRetry(attempt, maxAttempts, failure, response, requestContext, request, failureThrowable, lastDelayMillis, attemptOffset);
        }
    }

    private long scheduleRetry(
            int attempt,
            int maxAttempts,
            String failure,
            WireResponse response,
            RequestContext requestContext,
            WireRequest request,
            Throwable failureThrowable,
            long previousDelayMillis,
            int attemptOffset
    ) throws InterruptedException {
        closeIntermediateStream(response);
        long delayMillis = config.retryPolicy().nextDelayMillis(attempt, previousDelayMillis, RetryAfter.delayMillis(response), ThreadLocalRandom.current());
        String traceId = TraceIdExtractor.extract(request);
        HttpDiagnosticLogger.logRetry(config.logger(), attempt, maxAttempts, failure, delayMillis, traceId);
        MetricsNotifier.retry(config.metricsListener(), config.logger(), requestContext, request, attempt + attemptOffset, failureThrowable, delayMillis);
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
        return delayMillis;
    }

    boolean isRetryableStatus(WireResponse response) {
        int status = response.statusCode();

        if (config.isExpectedStatus(status)) {
            return false; // success — the loop ends
        }
        if (config.onErrorParser() != null && config.onErrorParser().canParse(status) && !config.retryPolicy().isRetryOnErrorStatuses()) {
            return false; // a declared @OnError status is a terminal contract by default
        }
        if (!config.retryPolicy().getRetryableStatuses().contains(status)) {
            return false; // not in the retryable set — a bare 404 is a deterministic client error
        }
        return config.retryPolicy().isRetryNonIdempotent() || !isNonIdempotent(config.httpMethod());
    }

    static boolean isNonIdempotent(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PATCH;
    }

    boolean probePayloadDecode(WireResponse response) {
        int status = response.statusCode();
        if (config.onErrorParser() != null && config.onErrorParser().canParse(status)) {
            return true; // @OnError classifies before the payload decode
        }
        if (!config.isExpectedStatus(status)) {
            return true; // the status check classifies before the payload decode
        }
        if (response.stream() != null) {
            return true; // byte binding — nothing is decoded, the decode cannot fail
        }
        byte[] bytes = response.body();
        String payload = (bytes != null && bytes.length > 0) ? new String(bytes, StandardCharsets.UTF_8) : null;
        if (config.optional()) {
            if (ResponseBody.isMissingResource(status) || payload == null) {
                return true;
            }
            if (String.class.equals(config.returnType()) || byte[].class.equals(config.returnType())
                    || Void.class.equals(config.returnType()) || void.class.equals(config.returnType())) {
                return true;
            }
            resolveCodec(response.headers()).deserialize(bytes, config.returnType(), ResponseBody.contentTypeOf(response.headers()));
            return true;
        }
        if (payload == null) {
            return true; // no body
        }
        if (String.class.equals(config.returnType()) || byte[].class.equals(config.returnType())) {
            return true; // returned raw, never decoded
        }
        if (Void.class.equals(config.returnType()) || void.class.equals(config.returnType())) {
            return true; // body discarded
        }
        resolveCodec(response.headers()).deserialize(bytes, config.returnType(), ResponseBody.contentTypeOf(response.headers()));
        return true;
    }

    private UnexpectedStatusException createUnexpectedStatusException(
            int statusCode, String rawBody, List<Header> responseHeaders) {
        return HttpErrorHandler.createUnexpectedStatusException(
                statusCode, rawBody, responseHeaders, resolveCodec(responseHeaders));
    }

    private PayloadCodec resolveCodec(List<Header> headers) {
        Function<List<Header>, PayloadCodec> resolver = config.codecResolver();
        return resolver != null ? resolver.apply(headers) : null;
    }

    CompletableFuture<WireResponse> executeAsync(
            WireRequest request,
            RequestContext requestContext,
            AtomicInteger attemptsRef,
            int attemptOffset,
            AsyncSender asyncSender
    ) {
        RetryPolicy retryPolicy = config.retryPolicy();
        if (!retryPolicy.isEnabled()) {
            long attemptStart = System.nanoTime();
            return asyncSender.send(request).whenComplete((response, failure) -> {
                Throwable unwrapped = failure != null ? unwrapCause(failure) : null;
                MetricsNotifier.attemptComplete(config.metricsListener(), config.logger(), requestContext, request, 1 + attemptOffset, response, unwrapped, attemptStart);
            });
        }
        return attemptAsync(request, 1, retryPolicy.maxAttempts(), requestContext, attemptsRef, 0L, attemptOffset, asyncSender);
    }

    private CompletableFuture<WireResponse> attemptAsync(
            WireRequest request,
            int attempt,
            int maxAttempts,
            RequestContext requestContext,
            AtomicInteger attemptsRef,
            long previousDelayMillis,
            int attemptOffset,
            AsyncSender asyncSender
    ) {
        if (attemptsRef != null) {
            attemptsRef.set(attempt);
        }
        boolean last = attempt == maxAttempts;
        long attemptStart = System.nanoTime();
        return asyncSender.send(request)
                .<CompletableFuture<WireResponse>>handle((response, failure) -> {
                    Throwable cause = failure != null ? unwrapCause(failure) : null;
                    if (cause != null) {
                        MetricsNotifier.attemptComplete(config.metricsListener(), config.logger(), requestContext, request, attempt + attemptOffset, null, cause, attemptStart);
                        if (!(cause instanceof IOException) || last || !config.retryPolicy().isRetryTransport()) {
                            return CompletableFuture.<WireResponse>failedFuture(cause);
                        }
                        return scheduleAsyncRetry(request, attempt, maxAttempts, cause.getClass().getSimpleName(), null, requestContext, cause, attemptsRef, previousDelayMillis, attemptOffset, asyncSender);
                    }
                    Throwable statusFailure = !config.isExpectedStatus(response.statusCode())
                            ? createUnexpectedStatusException(response.statusCode(), ResponseBody.extractText(response), response.headers())
                            : null;
                    MetricsNotifier.attemptComplete(config.metricsListener(), config.logger(), requestContext, request, attempt + attemptOffset, response, statusFailure, attemptStart);
                    if (last || !isRetryableStatus(response)) {
                        if (!last && config.retryPolicy().isRetryDecodeFailures()) {
                            try {
                                probePayloadDecode(response);
                            } catch (RuntimeException e) {
                                return scheduleAsyncRetry(request, attempt, maxAttempts,
                                        "decode (" + e.getClass().getSimpleName() + ")", response, requestContext, e, attemptsRef, previousDelayMillis, attemptOffset, asyncSender);
                            }
                        }
                        return CompletableFuture.<WireResponse>completedFuture(response);
                    }
                    return scheduleAsyncRetry(request, attempt, maxAttempts, "HTTP " + response.statusCode(), response, requestContext, statusFailure, attemptsRef, previousDelayMillis, attemptOffset, asyncSender);
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<WireResponse> scheduleAsyncRetry(
            WireRequest request,
            int attempt,
            int maxAttempts,
            String failure,
            WireResponse response,
            RequestContext requestContext,
            Throwable failureThrowable,
            AtomicInteger attemptsRef,
            long previousDelayMillis,
            int attemptOffset,
            AsyncSender asyncSender
    ) {
        closeIntermediateStream(response);
        long delayMillis = config.retryPolicy().nextDelayMillis(attempt, previousDelayMillis, RetryAfter.delayMillis(response), ThreadLocalRandom.current());
        String traceId = TraceIdExtractor.extract(request);
        HttpDiagnosticLogger.logRetry(config.logger(), attempt, maxAttempts, failure, delayMillis, traceId);
        MetricsNotifier.retry(config.metricsListener(), config.logger(), requestContext, request, attempt + attemptOffset, failureThrowable, delayMillis);
        if (delayMillis <= 0) {
            return attemptAsync(request, attempt + 1, maxAttempts, requestContext, attemptsRef, 0L, attemptOffset, asyncSender);
        }
        Executor scheduler = config.executor() != null
                ? CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, config.executor())
                : CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS);
        return CompletableFuture.runAsync(() -> {
        }, scheduler)
                .thenCompose(ignored -> attemptAsync(request, attempt + 1, maxAttempts, requestContext, attemptsRef, delayMillis, attemptOffset, asyncSender));
    }

    private void closeIntermediateStream(WireResponse response) {
        if (response != null) {
            ResponseBody.closeQuietly(response.stream(), config.logger());
        }
    }

    static Throwable unwrapCause(Throwable throwable) {
        if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
            Throwable cause = throwable.getCause();
            return cause != null ? cause : throwable;
        }
        return throwable;
    }
}
