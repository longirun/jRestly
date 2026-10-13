package ru.jrestly.fixtures;

import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.Retry;

import java.util.concurrent.CompletableFuture;

/**
 * Per-method {@code @Retry} fixture (ADR-0008, Aspect 2), in the shape of
 * {@link HttpVersionController}: one dedicated interface for the
 * annotation under test. Backoffs are 10–50 ms so the retry tests stay
 * fast.
 */
public interface RetryController {

    // Bare @Retry: the self-contained default policy (times = 1) — the
    // explicit off-switch (ADR-0008 Aspect 2)
    @Retry
    @Get(path = "/api/server-error")
    TestDto getWithBareRetry();

    // The same off-switch on the async circuit (CompletableFuture return)
    @Retry
    @Get(path = "/api/server-error")
    CompletableFuture<TestDto> getWithBareRetryAsync();

    // Self-contained policy while the client level stays off: 429 flaps to 200
    @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50)
    @Get(path = "/api/rate-limited")
    TestDto getRateLimitedWithRetry();

    // Idempotency opt-in for POST (RFC 9110 default: no retry)
    @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50, nonIdempotent = true)
    @Post(path = "/api/items")
    TestDto createItemWithNonIdempotentRetry(@RequestBody TestDto item);

    // @OnError opt-in: declared 429s participate in the retry decision,
    // and the final failure stays a typed HandledException
    @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50, onErrorStatuses = true)
    @OnError(statuses = {429}, errorObject = TestErrorDto.class)
    @Get(path = "/api/rate-limited")
    TestDto getRateLimitedWithOnErrorRetry();

    // Custom status set: replaces the default transient family entirely
    @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 20, statuses = {501})
    @Get(path = "/api/server-error")
    TestDto getWithCustomStatuses();
}
