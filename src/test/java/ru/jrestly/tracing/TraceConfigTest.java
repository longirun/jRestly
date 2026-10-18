package ru.jrestly.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ControllerValidationException;
import ru.jrestly.ControllerValidator;
import ru.jrestly.JRestlyClient;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Trace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for tracing configuration wiring (ADR-0042): the null ModuleInfo
 * default, SimpleModuleInfo and JRestlyClient.Builder propagation, and
 * ControllerValidator rejecting a blank {@code @Trace} header name. No network.
 */
class TraceConfigTest {

    @Test
    @DisplayName("ModuleInfo defaults return null TracePropagator")
    void shouldReturnDefaultNullPropagator() {
        ModuleInfo info = new SimpleModuleInfo.Builder()
                .baseUrl("https://api.example.com")
                .build();
        assertNull(info.getTracePropagator());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder configures trace propagators correctly")
    void shouldConfigureTracePropagatorsInModuleInfo() {
        SimpleModuleInfo infoCorrelation = new SimpleModuleInfo.Builder()
                .baseUrl("https://api.example.com")
                .correlationId("X-Correlation-ID")
                .build();
        assertNotNull(infoCorrelation.getTracePropagator());

        SimpleModuleInfo infoW3c = new SimpleModuleInfo.Builder()
                .baseUrl("https://api.example.com")
                .w3cTracing()
                .build();
        assertNotNull(infoW3c.getTracePropagator());

        SimpleModuleInfo infoW3cDisabled = new SimpleModuleInfo.Builder()
                .baseUrl("https://api.example.com")
                .w3cTracing(true)
                .w3cTracing(false)
                .build();
        assertNull(infoW3cDisabled.getTracePropagator());
    }

    @Test
    @DisplayName("JRestlyClient.Builder forwards trace settings to ModuleInfo")
    void shouldForwardSettingsInJRestlyClientBuilder() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .correlationId("X-Custom-Trace-ID", () -> "custom-id-999")
                .build();
        assertNotNull(client.getModuleInfo().getTracePropagator());
    }

    @Trace(header = "   ")
    interface InvalidInterfaceHeader {
        @Get("/test")
        String test();
    }

    interface InvalidMethodHeader {
        @Get("/test")
        @Trace(header = "")
        String test();
    }

    @Trace
    interface ValidTraceInterface {
        @Get("/test")
        @Trace(w3c = true)
        String test();
    }

    @Test
    @DisplayName("ControllerValidator detects blank header in @Trace on interface and method")
    void shouldValidateBlankHeaderInTrace() {
        List<String> ifaceProblems = ControllerValidator.validate(InvalidInterfaceHeader.class);
        assertFalse(ifaceProblems.isEmpty());
        assertTrue(ifaceProblems.stream().anyMatch(p -> p.contains("@Trace header must not be blank")));

        List<String> methodProblems = ControllerValidator.validate(InvalidMethodHeader.class);
        assertFalse(methodProblems.isEmpty());
        assertTrue(methodProblems.stream().anyMatch(p -> p.contains("@Trace header must not be blank")));

        List<String> validProblems = ControllerValidator.validate(ValidTraceInterface.class);
        assertTrue(validProblems.isEmpty());
    }
}
