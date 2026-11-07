package ru.jrestly.fixtures;

import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.Retry;
import ru.jrestly.annotation.SetAuthDetails;

import java.util.concurrent.CompletableFuture;

public interface RetryController {

    @Retry
    @Get(path = "/api/server-error")
    TestDto getWithBareRetry();

    @Retry
    @Get(path = "/api/server-error")
    CompletableFuture<TestDto> getWithBareRetryAsync();

    @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50)
    @Get(path = "/api/rate-limited")
    TestDto getRateLimitedWithRetry();

    @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50, nonIdempotent = true)
    @Post(path = "/api/items")
    TestDto createItemWithNonIdempotentRetry(@RequestBody TestDto item);

    @Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50, onErrorStatuses = true)
    @OnError(statuses = {429}, errorObject = TestErrorDto.class)
    @Get(path = "/api/rate-limited")
    TestDto getRateLimitedWithOnErrorRetry();

    @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 20, statuses = {501})
    @Get(path = "/api/server-error")
    TestDto getWithCustomStatuses();

    @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 50, decodeFailures = true)
    @Get(path = "/api/decode")
    TestDto getWithDecodeRetry();

    @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 50, decodeFailures = true)
    @Get(path = "/api/decode")
    CompletableFuture<TestDto> getWithDecodeRetryAsync();

    @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 50, decodeFailures = true)
    @SetAuthDetails(headerName = "access-token")
    @Get(path = "/api/decode-auth")
    TestDto getDecodeAuth();

    @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 50)
    @SetAuthDetails(headerName = "access-token")
    @Get(path = "/api/auth-check")
    TestDto getWithRetryAndAuthDetails();

    @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 50, respectRetryAfter = false)
    @Get(path = "/api/rate-limited-ignored")
    TestDto getWithRetryAfterIgnored();

    @Retry(times = 2, initialBackoffMs = 10, maxBackoffMs = 50, maxRetryAfterMs = 100)
    @Get(path = "/api/rate-limited-capped")
    TestDto getWithRetryAfterCapped();
}
