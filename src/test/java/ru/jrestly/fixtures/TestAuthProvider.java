package ru.jrestly.fixtures;

import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.AuthProvider;
import ru.jrestly.http.AuthHeaderProvider;

import java.util.List;

public class TestAuthProvider extends AuthHeaderProvider {

    private boolean authorized;

    public TestAuthProvider() {
        this.authorized = false;
    }

    public TestAuthProvider(boolean authorized) {
        this.authorized = authorized;
    }

    @Override
    public Pair<String, String> getAuthHeader() {
        Pair<String, String> localHeader = super.getAuthHeader();
        return localHeader != null ? localHeader : null;
    }

    @Override
    public boolean isAuthorized() {
        return authorized;
    }

    public void setAuthorized(boolean authorized) {
        this.authorized = authorized;
    }
}
