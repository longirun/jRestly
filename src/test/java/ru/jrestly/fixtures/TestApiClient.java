package ru.jrestly.fixtures;

import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.AbstractHttpClient;
import ru.jrestly.ModuleInfo;

import java.util.List;

public class TestApiClient extends AbstractHttpClient {

    public TestApiClient(ModuleInfo moduleInfo) {
        super(moduleInfo);
    }

    @Override
    protected void login() {
        List<Pair<String, String>> authHeaders = moduleInfo.getAuthProvider().getAuthHeaders();
        if (!authHeaders.isEmpty()) {
            moduleInfo.getEnvironment().setAuthDetails(authHeaders.get(0));
        }
    }
}
