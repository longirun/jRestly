package ru.jrestly;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import ru.jrestly.fixtures.TestApiClient;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestModuleInfo;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;

/**
 * Abstract harness for WireMock e2e tests: boots a dynamic-port
 * {@code WireMockServer} per test and wires the shared {@code TestModuleInfo},
 * {@code TestApiClient} and {@code TestController} fixtures bound to that port.
 * Extended by most {@code ru.jrestly} integration tests.
 */
public abstract class BaseWireMockTest {

    protected WireMockServer wireMockServer;
    protected TestApiClient client;
    protected TestController controller;
    protected TestModuleInfo moduleInfo;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());

        moduleInfo = new TestModuleInfo("http://localhost:" + wireMockServer.port());
        client = new TestApiClient(moduleInfo);
        controller = client.get(TestController.class);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
        client.close();
    }

    protected String baseUrl() {
        return "http://localhost:" + wireMockServer.port();
    }
}
