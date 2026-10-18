package ru.jrestly.fixtures;

import ru.jrestly.http.AuthHeaderProvider;
import ru.jrestly.http.Header;

import java.util.List;

/**
 * Test {@link AuthHeaderProvider} with a switchable {@code isAuthorized}
 * flag and a capture counter: the counter pins "classified exactly once"
 * for {@code @SetAuthDetails} capture across decode-retry
 * (ADR-0011, Aspect 7).
 */
public class TestAuthProvider extends AuthHeaderProvider {

    private boolean authorized;
    private int captureCount;

    public TestAuthProvider() {
        this.authorized = false;
    }

    public TestAuthProvider(boolean authorized) {
        this.authorized = authorized;
    }

    @Override
    public boolean isAuthorized() {
        return authorized;
    }

    public void setAuthorized(boolean authorized) {
        this.authorized = authorized;
    }

    // ADR-0011 Aspect 7 scenario 5: the capture count. The replace semantics
    // of AuthHeaderProvider would keep the last value and hide a double call,
    // so "classified exactly once" is pinned by counting invocations.
    @Override
    public void captureAuthDetails(String headerName, List<Header> responseHeaders) {
        captureCount++;
        super.captureAuthDetails(headerName, responseHeaders);
    }

    public int getCaptureCount() {
        return captureCount;
    }
}
