package ru.jrestly.fixtures;

import org.apache.commons.lang3.tuple.Pair;
import ru.jrestly.Environment;

public class TestEnvironment implements Environment<Pair<String, String>> {

    private final String baseUrl;
    private Pair<String, String> authDetails;

    public TestEnvironment(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public String getUrl() {
        return baseUrl;
    }

    @Override
    public Pair<String, String> getAuthDetails() {
        return authDetails;
    }

    @Override
    public void setAuthDetails(Pair<String, String> authDetails) {
        this.authDetails = authDetails;
    }
}
