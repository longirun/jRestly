package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.fixtures.TestModuleInfo;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declarative timeout configuration (ADR-0027): the 30s default socket timeout,
 * the Duration and int builder overloads of {@code SimpleModuleInfo} /
 * {@code JRestlyClient} with fail-fast validation, and {@code ControllerValidator}
 * rejecting non-positive {@code @Timeout} on methods and interfaces. Pure unit tests.
 */
class TimeoutConfigTest {

    interface InvalidMethodTimeoutApi {
        @Timeout(0)
        @Get("/invalid-method-zero")
        String zeroTimeout();

        @Timeout(-100)
        @Get("/invalid-method-negative")
        String negativeTimeout();
    }

    @Timeout(0)
    interface InvalidInterfaceTimeoutApi {
        @Get("/ping")
        String ping();
    }

    @Test
    @DisplayName("ModuleInfo and SimpleModuleInfo provide default 30s socket timeout")
    void defaultSocketTimeout() {
        TestModuleInfo defaultInfo = new TestModuleInfo("http://localhost");
        assertEquals(30_000, defaultInfo.getSocketTimeout());

        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost")
                .build();
        assertEquals(30_000, info.getSocketTimeout());
        assertEquals(5_000, info.getConnectTimeout());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder Duration overloads configure socket and connect timeouts")
    void simpleModuleInfoDurationOverloads() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost")
                .connectTimeout(Duration.ofSeconds(2))
                .socketTimeout(Duration.ofSeconds(45))
                .build();

        assertEquals(2_000, info.getConnectTimeout());
        assertEquals(45_000, info.getSocketTimeout());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder rejects null and negative durations")
    void simpleModuleInfoDurationValidation() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder()
                .baseUrl("http://localhost");

        assertThrows(NullPointerException.class, () -> builder.connectTimeout((Duration) null));
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> builder.connectTimeout(Duration.ofMillis(-1)));
        assertTrue(e1.getMessage().contains("connectTimeout duration must not be negative"));

        assertThrows(NullPointerException.class, () -> builder.socketTimeout((Duration) null));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> builder.socketTimeout(Duration.ofSeconds(-5)));
        assertTrue(e2.getMessage().contains("socketTimeout duration must not be negative"));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder int overloads reject negative timeouts (parity with the Duration overloads)")
    void simpleModuleInfoIntTimeoutValidation() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder()
                .baseUrl("http://localhost");

        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> builder.connectTimeout(-1));
        assertTrue(e1.getMessage().contains("connectTimeout must not be negative"));

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> builder.socketTimeout(-5_000));
        assertTrue(e2.getMessage().contains("socketTimeout must not be negative"));

        // positive values are still accepted through both overload families
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost")
                .connectTimeout(1_200)
                .socketTimeout(8_000)
                .build();
        assertEquals(1_200, info.getConnectTimeout());
        assertEquals(8_000, info.getSocketTimeout());

        // the JRestlyClient facade inherits the same fail-fast validation
        JRestlyClient.Builder clientBuilder = JRestlyClient.builder();
        assertThrows(IllegalArgumentException.class, () -> clientBuilder.connectTimeout(-1));
        assertThrows(IllegalArgumentException.class, () -> clientBuilder.socketTimeout(-1));
    }

    @Test
    @DisplayName("JRestlyClient.Builder Duration overloads configure timeouts and validate inputs")
    void jRestlyClientDurationOverloads() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost")
                .connectTimeout(Duration.ofMillis(1500))
                .socketTimeout(Duration.ofSeconds(12))
                .build();

        assertEquals(1500, client.getModuleInfo().getConnectTimeout());
        assertEquals(12_000, client.getModuleInfo().getSocketTimeout());

        JRestlyClient.Builder builder = JRestlyClient.builder();
        assertThrows(NullPointerException.class, () -> builder.connectTimeout((Duration) null));
        assertThrows(NullPointerException.class, () -> builder.socketTimeout((Duration) null));
        assertThrows(IllegalArgumentException.class, () -> builder.connectTimeout(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> builder.socketTimeout(Duration.ofMillis(-1)));
    }

    @Test
    @DisplayName("ControllerValidator detects non-positive @Timeout on methods and interfaces")
    void controllerValidatorRejectsNonPositiveTimeouts() {
        List<String> methodProblems = ControllerValidator.validate(InvalidMethodTimeoutApi.class);
        assertTrue(methodProblems.stream().anyMatch(p -> p.contains("zeroTimeout(): @Timeout value must be strictly positive, was 0")));
        assertTrue(methodProblems.stream().anyMatch(p -> p.contains("negativeTimeout(): @Timeout value must be strictly positive, was -100")));

        List<String> ifaceProblems = ControllerValidator.validate(InvalidInterfaceTimeoutApi.class);
        assertTrue(ifaceProblems.stream().anyMatch(p -> p.contains("InvalidInterfaceTimeoutApi: @Timeout value must be strictly positive, was 0")));

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost")
                .validateControllers(true)
                .build();

        ControllerValidationException ex1 = assertThrows(ControllerValidationException.class,
                () -> client.createController(InvalidMethodTimeoutApi.class));
        assertTrue(ex1.getMessage().contains("strictly positive"));

        ControllerValidationException ex2 = assertThrows(ControllerValidationException.class,
                () -> client.createController(InvalidInterfaceTimeoutApi.class));
        assertTrue(ex2.getMessage().contains("strictly positive"));
    }
}
