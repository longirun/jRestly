package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@code AuthHeaderProvider}: the authorization state always
 * agrees with the emitted headers (a credential needs both name and value)
 * across updates, and concurrent mutations never expose a torn name/value
 * pair. No network.
 */
class AuthHeaderProviderTest {

    @Test
    @DisplayName("a credential without a value is neither authorized nor sent")
    void incompleteCredentialIsNotSent() {
        AuthHeaderProvider nameOnly = new AuthHeaderProvider("Authorization", null);
        assertTrue(nameOnly.getAuthHeaders().isEmpty());
        assertFalse(nameOnly.isAuthorized(),
                "a name-only initial credential must not authorize the client");

        AuthHeaderProvider valueOnly = new AuthHeaderProvider(null, "Bearer x");
        assertTrue(valueOnly.getAuthHeaders().isEmpty());
        assertFalse(valueOnly.isAuthorized(),
                "a value-only initial credential must not authorize the client");
    }

    @Test
    @DisplayName("a complete initial pair is authorized and emitted")
    void completeInitialPairIsAuthorized() {
        AuthHeaderProvider provider = new AuthHeaderProvider("Authorization", "Bearer x");

        assertEquals(List.of(new Header("Authorization", "Bearer x")), provider.getAuthHeaders());
        assertTrue(provider.isAuthorized());
    }

    @Test
    @DisplayName("an incomplete update neither authorizes nor is sent")
    void incompleteUpdateIsNotSent() {
        AuthHeaderProvider provider = new AuthHeaderProvider();

        provider.updateAuthHeader(null, "Bearer y");
        assertTrue(provider.getAuthHeaders().isEmpty());
        assertFalse(provider.isAuthorized(), "a value-only captured credential must not authorize");

        provider.updateAuthHeader("Authorization", null);
        assertTrue(provider.getAuthHeaders().isEmpty());
        assertFalse(provider.isAuthorized(), "a name-only captured credential must not authorize");
    }

    @Test
    @DisplayName("a complete update takes precedence over the initial pair and is clearable")
    void updateOverridesInitialPair() {
        AuthHeaderProvider provider = new AuthHeaderProvider("Authorization", "Bearer initial");

        provider.updateAuthHeader("X-Manual", "captured");
        assertEquals(List.of(new Header("X-Manual", "captured")), provider.getAuthHeaders());
        assertTrue(provider.isAuthorized());

        provider.updateAuthHeader(null, null);
        assertEquals(List.of(new Header("Authorization", "Bearer initial")), provider.getAuthHeaders());
        assertTrue(provider.isAuthorized());
    }

    @Test
    @DisplayName("isAuthorized() always agrees with getAuthHeaders()")
    void authorizationInvariantHoldsAcrossStates() {
        String[] names = {null, "X-Auth"};
        String[] values = {null, "token"};
        String[] updateNames = {null, null, "X-New", "X-New"};
        String[] updateValues = {null, "token", null, "token"};

        for (String initialName : names) {
            for (String initialValue : values) {
                for (int i = 0; i < updateNames.length; i++) {
                    AuthHeaderProvider provider = new AuthHeaderProvider(initialName, initialValue);
                    provider.updateAuthHeader(updateNames[i], updateValues[i]);

                    String state = "initial=(" + initialName + "," + initialValue + ") update=("
                            + updateNames[i] + "," + updateValues[i] + ")";
                    assertEquals(provider.isAuthorized(), !provider.getAuthHeaders().isEmpty(),
                            "isAuthorized() disagrees with getAuthHeaders() for " + state);
                }
            }
        }
    }

    @Test
    @DisplayName("concurrent updates never expose a torn name/value pair")
    void concurrentUpdatesNeverExposeTornPair() throws Exception {
        AuthHeaderProvider provider = new AuthHeaderProvider();
        Header first = new Header("X-First", "1");
        Header second = new Header("X-Second", "2");

        AtomicReference<String> torn = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Runnable writer = () -> {
                await(start);
                for (int i = 0; i < 500_000 && torn.get() == null; i++) {
                    provider.updateAuthHeader(first.name(), first.value());
                    provider.updateAuthHeader(second.name(), second.value());
                    provider.updateAuthHeader(null, null);
                }
            };
            Runnable reader = () -> {
                await(start);
                for (int i = 0; i < 500_000 && torn.get() == null; i++) {
                    List<Header> headers = provider.getAuthHeaders();
                    if (!headers.isEmpty()) {
                        Header observed = headers.get(0);
                        if (!observed.equals(first) && !observed.equals(second)) {
                            torn.compareAndSet(null, observed.toString());
                        }
                    }
                }
            };
            pool.submit(writer);
            pool.submit(writer);
            pool.submit(reader);
            pool.submit(reader);
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "stress threads did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertNull(torn.get(), "observed a torn credential pair: " + torn.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                fail("start latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for start");
        }
    }
}
