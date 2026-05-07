package ru.jrestly.fixtures;

import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.AbstractHttpClient;
import ru.jrestly.ModuleInfo;

public class TestApiClient extends AbstractHttpClient {

    public TestApiClient(ModuleInfo moduleInfo) {
        super(moduleInfo);
    }

    @Override
    protected void login() {
        Pair<String, String> authHeader = moduleInfo.getAuthProvider().getAuthHeader();
        if (authHeader != null) {
            moduleInfo.getEnvironment().setAuthDetails(authHeader);
        }
    }
}
