package ru.jrestly.auth;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Package-private thread-safe implementation of {@link TokenManager} guaranteeing single-flight
 * token acquisition across concurrent callers.
 */
class SingleFlightTokenManager implements TokenManager {

    private final Supplier<Token> supplier;
    private final Duration refreshBuffer;
    private final AtomicReference<CompletableFuture<Token>> inFlight = new AtomicReference<>();
    private volatile Token cachedToken;

    SingleFlightTokenManager(Supplier<Token> supplier, Duration refreshBuffer) {
        this.supplier = Objects.requireNonNull(supplier, "supplier");
        this.refreshBuffer = refreshBuffer != null ? refreshBuffer : Duration.ZERO;
    }

    @Override
    public String getToken() {
        Token token = this.cachedToken;
        if (token != null && !token.isExpired(refreshBuffer)) {
            return token.value();
        }
        return acquireToken();
    }

    private String acquireToken() {
        while (true) {
            Token token = this.cachedToken;
            if (token != null && !token.isExpired(refreshBuffer)) {
                return token.value();
            }

            CompletableFuture<Token> current = inFlight.get();
            if (current != null) {
                try {
                    Token result = current.join();
                    if (!result.isExpired(refreshBuffer)) {
                        return result.value();
                    }
                } catch (Throwable ignored) {
                    // Previous attempt failed, loop again to initiate or join fresh attempt
                }
            }

            CompletableFuture<Token> future = new CompletableFuture<>();
            if (inFlight.compareAndSet(current, future)) {
                try {
                    Token fresh = supplier.get();
                    if (fresh == null) {
                        throw new IllegalStateException("Token supplier returned null Token");
                    }
                    this.cachedToken = fresh;
                    future.complete(fresh);
                    // Publish the token, then clear only our own future: an invalidate()
                    // may have superseded it with a newer in-flight acquisition that
                    // another caller installed, and that one must stay shared.
                    inFlight.compareAndSet(future, null);
                    return fresh.value();
                } catch (Throwable t) {
                    this.cachedToken = null;
                    inFlight.compareAndSet(future, null);
                    future.completeExceptionally(t);
                    if (t instanceof RuntimeException re) {
                        throw re;
                    } else if (t instanceof Error err) {
                        throw err;
                    } else {
                        throw new RuntimeException("Failed to acquire token", t);
                    }
                }
            }
        }
    }

    @Override
    public void invalidate() {
        this.cachedToken = null;
        this.inFlight.set(null);
    }
}
