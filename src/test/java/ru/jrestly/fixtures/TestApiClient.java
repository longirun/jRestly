package ru.jrestly.fixtures;

import ru.jrestly.AbstractHttpClient;
import ru.jrestly.ModuleInfo;
import ru.jrestly.http.Header;

import java.util.List;

/**
 * Test {@link AbstractHttpClient}: its {@code login()} pulls the ambient
 * auth headers from the {@code AuthProvider} into the environment —
 * the background-login path of the auth suites.
 */
public class TestApiClient extends AbstractHttpClient {

    public TestApiClient(ModuleInfo moduleInfo) {
        super(moduleInfo);
    }

    @Override
    protected void login() {
        List<Header> authHeaders = moduleInfo.getAuthProvider().getAuthHeaders();
        if (!authHeaders.isEmpty()) {
            moduleInfo.getEnvironment().setAuthDetails(authHeaders.get(0));
        }
    }
}
