package ru.jrestly.fixtures;

import ru.jrestly.Environment;
import ru.jrestly.http.Header;

/**
 * Test {@link Environment}: holds the base URL and the captured
 * {@link Header} auth details, as used by {@link TestModuleInfo}.
 */
public class TestEnvironment implements Environment<Header> {

    private final String baseUrl;
    private Header authDetails;

    public TestEnvironment(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public String getUrl() {
        return baseUrl;
    }

    @Override
    public Header getAuthDetails() {
        return authDetails;
    }

    @Override
    public void setAuthDetails(Header authDetails) {
        this.authDetails = authDetails;
    }
}
