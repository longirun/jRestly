package ru.jrestly.fixtures;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shared CompletableFuture semantics for the async tests: {@code join()}
 * wraps the stored failure in a {@link CompletionException}; the contract
 * under test is the carried exception.
 */
public final class TestFutures {

    private TestFutures() {
    }

    // The caller statically names the carried exception's type; the contract
    // under test is that the cause is exactly that type
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T causeOf(Runnable joining) {
        CompletionException exception = assertThrows(CompletionException.class, joining::run);
        return (T) exception.getCause();
    }
}
