package ru.jrestly.fixtures;

import ru.jrestly.AbstractHttpClient;
import ru.jrestly.ModuleInfo;
import ru.jrestly.http.Header;

import java.util.List;

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
