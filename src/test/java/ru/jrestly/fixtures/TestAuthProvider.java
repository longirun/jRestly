package ru.jrestly.fixtures;

import ru.jrestly.http.AuthHeaderProvider;

public class TestAuthProvider extends AuthHeaderProvider {

    private boolean authorized;

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
}
