package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.http.ClientErrorException;
import ru.jrestly.http.Header;
import ru.jrestly.http.ServerErrorException;
import ru.jrestly.http.UnexpectedStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OnErrorConfigTest {

    public record ErrorA(String a) {}
    public record ErrorB(String b) {}
    public record ErrorDefault(String msg) {}

    @SuppressWarnings("serial")
    public static class CustomNotFoundException extends RuntimeException {
        public CustomNotFoundException(String message) {
            super(message);
        }
    }

    public interface SuperApi {
        @OnError(statuses = {400, 401}, errorObject = ErrorA.class)
        @Get(path = "/super")
        String callSuper();
    }

    public interface SubApi extends SuperApi {
        @OnError(statuses = {401}, errorObject = ErrorB.class)
        @Get(path = "/sub")
        String callSub();

        @OnError(statuses = {400}, errorObject = ErrorB.class)
        @Get(path = "/sub-override")
        String callSubOverride();
    }

    public interface ConflictingMethodApi {
        @OnError(statuses = {400}, errorObject = ErrorA.class)
        @OnError(statuses = {400}, errorObject = ErrorB.class)
        @Get(path = "/conflict")
        String conflict();
    }

    @OnError(statuses = {500}, errorObject = ErrorA.class)
    @OnError(statuses = {500}, errorObject = ErrorB.class)
    public interface ConflictingInterfaceApi {
        @Get(path = "/ok")
        String ok();
    }

    public interface InvalidStatusApi {
        @OnError(statuses = {99}, errorObject = ErrorA.class)
        @Get(path = "/invalid")
        String invalid();
    }

    @Test
    @DisplayName("ClientErrorException and ServerErrorException hierarchy and properties")
    void exceptionHierarchy() {
        Header header = new Header("Content-Type", "application/json");
        ClientErrorException clientErr = new ClientErrorException(404, "not found", List.of(header));
        assertInstanceOf(UnexpectedStatusException.class, clientErr);
        assertEquals(404, clientErr.getStatusCode());
        assertEquals("not found", clientErr.getRawBody());
        assertEquals(1, clientErr.getResponseHeaders().size());

        ServerErrorException serverErr = new ServerErrorException(503, "unavailable", List.of(header));
        assertInstanceOf(UnexpectedStatusException.class, serverErr);
        assertEquals(503, serverErr.getStatusCode());
        assertEquals("unavailable", serverErr.getRawBody());
    }

    @Test
    @DisplayName("ControllerValidator detects invalid HTTP status in @OnError")
    void validatorDetectsInvalidStatus() {
        List<String> problems = ControllerValidator.validate(InvalidStatusApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("@OnError status must be a valid HTTP status"));
    }

    @Test
    @DisplayName("ControllerValidator detects conflicting @OnError on method")
    void validatorDetectsConflictingMethodOnError() {
        List<String> problems = ControllerValidator.validate(ConflictingMethodApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("conflicting classes"));
    }

    @Test
    @DisplayName("ControllerValidator detects conflicting @OnError on interface")
    void validatorDetectsConflictingInterfaceOnError() {
        List<String> problems = ControllerValidator.validate(ConflictingInterfaceApi.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("conflicting classes"));
    }

    @Test
    @DisplayName("Proxy creation validates hierarchy without errors")
    void proxyHierarchyResolution() {
        JRestlyClient client = new JRestlyClient.Builder()
                .baseUrl("http://localhost:8080")
                .build();
        try {
            SuperApi superProxy = client.get(SuperApi.class);
            assertNotNull(superProxy);
            SubApi subProxy = client.get(SubApi.class);
            assertNotNull(subProxy);
        } finally {
            client.close();
        }
    }
}
