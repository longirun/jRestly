package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestController;
import ru.jrestly.fixtures.TestDto;
import ru.jrestly.http.HttpTemplate;
import ru.jrestly.http.HttpTemplateBuilder;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PutMethodTest extends BaseWireMockTest {

    @Test
    @DisplayName("PUT with @RequestBody sends JSON and deserializes response")
    void putWithJsonBody() {
        stubFor(put(urlEqualTo("/api/items/7"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"name\":\"replaced\",\"description\":\"full update\"}")));

        TestDto body = new TestDto(7L, "replaced", "full update");
        TestDto result = controller.putItem(7L, body);

        assertNotNull(result);
        assertEquals(7L, result.id());
        assertEquals("replaced", result.name());

        verify(putRequestedFor(urlEqualTo("/api/items/7"))
                .withHeader("Content-Type", containing("application/json")));
    }

    @Test
    @DisplayName("PUT with a body logs the body in request log")
    void putWithBodyIsAcceptedAndLogged() {
        stubFor(put(urlEqualTo("/api/items/7"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"name\":\"replaced\",\"description\":\"full update\"}")));

        LoggingTestClient loggingClient = new LoggingTestClient(moduleInfo);
        try {
            TestController loggingController = loggingClient.get(TestController.class);
            TestDto result = loggingController.putItem(7L, new TestDto(7L, "replaced", "full update"));

            assertNotNull(result);
            assertEquals(7L, result.id());

            String requestLog = loggingClient.requestLog();
            assertTrue(requestLog.contains("Request: PUT"), requestLog);
            assertTrue(requestLog.contains("\"name\":\"replaced\""), requestLog);
        } finally {
            loggingClient.close();
        }

        verify(putRequestedFor(urlEqualTo("/api/items/7")));
    }

    private final class LoggingTestClient extends AbstractHttpClient {
        private final CapturingSystemLogger logger = new CapturingSystemLogger();

        LoggingTestClient(ModuleInfo moduleInfo) {
            super(moduleInfo);
        }

        @Override
        protected Proxy createController(Class<?> classInterface) {
            return (Proxy) Proxy.newProxyInstance(
                    getClassLoader(),
                    new Class<?>[] {classInterface},
                    (proxy, method, args) -> {
                        if (isLoginInBackgroundNeeded(method)) {
                            login();
                        }
                        HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method, args, classInterface);
                        HttpTemplate template = builder.build();
                        template.setLogger(logger);
                        return template.exchange();
                    }
            );
        }

        String requestLog() {
            return logger.messages();
        }
    }

    private static final class CapturingSystemLogger implements System.Logger {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "jrestly-capture";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            messages.add(params == null || params.length == 0 ? format : String.format(format, params));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Throwable thrown) {
            messages.add(format + (thrown == null ? "" : " cause=" + thrown));
        }

        String messages() {
            return String.join("\n", messages);
        }
    }
}
