package ru.jrestly;

import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Compress;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.fixtures.BrokenController;
import ru.jrestly.fixtures.TestController;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eager controller validation: a broken controller fails proxy creation with a
 * {@code ControllerValidationException} carrying the controller and full problem list;
 * {@code validateControllers(false)} defers the failure to the first call; valid
 * controllers pass (including streaming return shapes). No network.
 */
public class ControllerValidationTest {

    @Test
    void throwsOnGetWithFullProblemListByDefault() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();

        ControllerValidationException e = assertThrows(ControllerValidationException.class,
                () -> client.get(BrokenController.class));

        assertEquals(BrokenController.class, e.getController());
        assertEquals(4, e.getProblems().size());
        assertTrue(e.getMessage().contains("noHttpAnnotation"));
        assertTrue(e.getMessage().contains("multipleHttpAnnotations"));
        assertTrue(e.getMessage().contains("multipartWithoutParams"));
        assertTrue(e.getMessage().contains("formWithoutParams"));
    }

    @Test
    void disabledValidationKeepsPerMethodFailureOnCall() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1")
                .validateControllers(false)
                .build();
        BrokenController broken = client.get(BrokenController.class);

        assertThrows(UnsupportedOperationException.class, broken::noHttpAnnotation);
    }

    @Test
    void validControllerPassesEagerValidation() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();

        assertNotNull(client.get(TestController.class));
    }

    interface StreamController {
        @ru.jrestly.annotation.Get(path = "/api/stream")
        java.io.InputStream stream();

        @ru.jrestly.annotation.Get(path = "/api/stream/async")
        java.util.concurrent.CompletableFuture<java.io.InputStream> streamAsync();

        @ru.jrestly.annotation.Get(path = "/api/stream/lines")
        java.util.stream.Stream<String> lines();

        @ru.jrestly.annotation.Get(path = "/api/stream/lines/async")
        java.util.concurrent.CompletableFuture<java.util.stream.Stream<String>> linesAsync();

        @ru.jrestly.annotation.Get(path = "/api/stream/events")
        java.util.stream.Stream<ru.jrestly.http.ServerSentEvent> events();

        @ru.jrestly.annotation.Get(path = "/api/stream/events/async")
        java.util.concurrent.CompletableFuture<java.util.stream.Stream<ru.jrestly.http.ServerSentEvent>> eventsAsync();
    }

    @Test
    void streamReturnTypesPassValidation() {
        assertTrue(ControllerValidator.validate(StreamController.class).isEmpty());

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertNotNull(client.get(StreamController.class));
    }

    @ru.jrestly.annotation.RequestHeader(name = "  ", value = "v")
    interface BlankClassHeaderController {
        @ru.jrestly.annotation.Get(path = "/test")
        String call();
    }

    @ru.jrestly.annotation.RequestDefaultParam(name = "", value = "v")
    interface BlankClassDefaultParamController {
        @ru.jrestly.annotation.Get(path = "/test")
        String call();
    }

    interface BlankMethodHeaderAndParamController {
        @ru.jrestly.annotation.Get(path = "/test-header")
        @ru.jrestly.annotation.RequestHeader(name = "", value = "v")
        String blankHeader();

        @ru.jrestly.annotation.Get(path = "/test-param")
        @ru.jrestly.annotation.RequestDefaultParam(name = "   ", value = "v")
        String blankParam();
    }

    @Test
    void rejectsNullCompressAlgorithmAtValidationSeam() {
        // A null enum member cannot be expressed at the call site (JLS 9.7.1: an enum
        // annotation value must be an enum constant), so the guard is exercised through
        // the validation seam with a proxy annotation instance (ADR-0041 §4).
        List<String> problems = new ArrayList<>();
        ControllerValidator.validateCompress("Api", compressAnnotation(null, 0), problems);
        assertEquals(List.of("Api: @Compress algorithm must not be null"), problems);

        List<String> valid = new ArrayList<>();
        ControllerValidator.validateCompress("Api", compressAnnotation(CompressionAlgorithm.GZIP, 0), valid);
        assertTrue(valid.isEmpty());
    }

    private static Compress compressAnnotation(CompressionAlgorithm algorithm, int minSizeBytes) {
        return (Compress) Proxy.newProxyInstance(
                Compress.class.getClassLoader(),
                new Class<?>[]{Compress.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "algorithm" -> algorithm;
                    case "minSizeBytes" -> minSizeBytes;
                    case "enabled" -> true;
                    case "annotationType" -> Compress.class;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "proxy @Compress";
                });
    }

    interface InvalidRetryController {
        @ru.jrestly.annotation.Get(path = "/a")
        @ru.jrestly.annotation.Retry(times = 0)
        String timesZero();

        @ru.jrestly.annotation.Get(path = "/b")
        @ru.jrestly.annotation.Retry(times = 3, initialBackoffMs = 5_000, maxBackoffMs = 100)
        String invertedBackoff();

        @ru.jrestly.annotation.Get(path = "/c")
        @ru.jrestly.annotation.Retry(times = 2, multiplier = 0.5)
        String lowMultiplier();

        @ru.jrestly.annotation.Get(path = "/d")
        @ru.jrestly.annotation.Retry(times = 2, statuses = {999})
        String badStatus();
    }

    interface ValidRetryController {
        @ru.jrestly.annotation.Get(path = "/ok")
        @ru.jrestly.annotation.Retry(times = 3, initialBackoffMs = 10, maxBackoffMs = 50, statuses = {408, 429, 503})
        String ok();
    }

    @Test
    void rejectsInvalidRetryConfigurationEagerly() {
        // @Retry is the only declarative annotation whose invariants lived solely in
        // its policy builder: a misconfigured method escaped eager validation and threw
        // a bare IllegalStateException at the first call. It now joins the same channel.
        List<String> problems = ControllerValidator.validate(InvalidRetryController.class);
        assertEquals(4, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.contains("timesZero(): @Retry times must be >= 1")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("invertedBackoff(): @Retry initialBackoffMs must not exceed maxBackoffMs")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("lowMultiplier(): @Retry multiplier must be >= 1.0")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("badStatus(): @Retry status must be in [100, 599]")));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(InvalidRetryController.class));

        // no false positive on a valid policy
        assertTrue(ControllerValidator.validate(ValidRetryController.class).isEmpty());
    }

    interface BlankAuthDetailsController {
        @ru.jrestly.annotation.Get(path = "/login")
        @ru.jrestly.annotation.SetAuthDetails(headerName = "")
        String login();
    }

    interface ValidAuthDetailsController {
        @ru.jrestly.annotation.Get(path = "/login")
        @ru.jrestly.annotation.SetAuthDetails(headerName = "access-token")
        String login();
    }

    @Test
    void rejectsBlankSetAuthDetailsHeaderNameEagerly() {
        // @SetAuthDetails.headerName is a required name attribute like @Trace.header /
        // @Cookie.name, but a blank value silently disabled credential capture: the
        // response-header lookup matched nothing, so the auth token was never written
        // back. It now joins the eager-validation channel (ADR-0046).
        List<String> problems = ControllerValidator.validate(BlankAuthDetailsController.class);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("login(): @SetAuthDetails headerName must not be blank"));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(BlankAuthDetailsController.class));

        // no false positive on a properly named capture
        assertTrue(ControllerValidator.validate(ValidAuthDetailsController.class).isEmpty());
    }

    interface BlankMultipartPartNameController {
        @ru.jrestly.annotation.Post(path = "/upload", requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String upload(@ru.jrestly.annotation.MultipartFormFile(partName = "  ") java.io.File file);
    }

    interface ValidMultipartPartNameController {
        @ru.jrestly.annotation.Post(path = "/upload", requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String upload(@ru.jrestly.annotation.MultipartFormFile(partName = "file") java.io.File file);
    }

    @Test
    void rejectsBlankMultipartFormFilePartNameEagerly() {
        // @MultipartFormFile.partName is a required name attribute (no default) and,
        // unlike its siblings @RequestParam/@RequestHeader/@Cookie, a blank value does
        // NOT fall back to the Java parameter name: it is put on the wire verbatim as
        // name="", so the server silently cannot associate the part with a form field.
        // It now joins the eager-validation channel (ADR-0024).
        List<String> problems = ControllerValidator.validate(BlankMultipartPartNameController.class);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("upload(): @MultipartFormFile partName must not be blank"));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(BlankMultipartPartNameController.class));

        // no false positive on a properly named part
        assertTrue(ControllerValidator.validate(ValidMultipartPartNameController.class).isEmpty());
    }

    @Test
    void rejectsBlankHeaderOrParamNameOnClassAndMethod() {
        var classHeaderProblems = ControllerValidator.validate(BlankClassHeaderController.class);
        assertEquals(1, classHeaderProblems.size());
        assertTrue(classHeaderProblems.getFirst().contains("@RequestHeader name must not be blank"));

        var classParamProblems = ControllerValidator.validate(BlankClassDefaultParamController.class);
        assertEquals(1, classParamProblems.size());
        assertTrue(classParamProblems.getFirst().contains("@RequestDefaultParam name must not be blank"));

        var methodProblems = ControllerValidator.validate(BlankMethodHeaderAndParamController.class);
        assertEquals(2, methodProblems.size());
        assertTrue(methodProblems.stream().anyMatch(p -> p.contains("blankHeader(): @RequestHeader name must not be blank")));
        assertTrue(methodProblems.stream().anyMatch(p -> p.contains("blankParam(): @RequestDefaultParam name must not be blank")));
    }

    interface UnresolvedPlaceholderController {
        @ru.jrestly.annotation.Get(path = "/users/${id}")
        String getUser();
    }

    interface UnusedPathVariableController {
        @ru.jrestly.annotation.Get(path = "/users")
        String getUser(@ru.jrestly.annotation.PathVariable(name = "id") String id);
    }

    interface ValidPathVariableController {
        @ru.jrestly.annotation.Get(path = "/users/${id}")
        String getUser(@ru.jrestly.annotation.PathVariable(name = "id") String id);
    }

    @Test
    void rejectsPathVariableTemplateMismatchEagerly() {
        // ADR-0025: the path template and its @PathVariable parameters must agree.
        // An unresolved ${id} placeholder is only rejected at send time by
        // URI.create ("Illegal character in path"), and a @PathVariable with no
        // matching placeholder is silently dropped from the URL. Both now join the
        // eager-validation channel.
        List<String> unresolved = ControllerValidator.validate(UnresolvedPlaceholderController.class);
        assertEquals(1, unresolved.size());
        assertTrue(unresolved.getFirst().contains("getUser(): path placeholder '${id}' has no matching @PathVariable parameter"));

        List<String> unused = ControllerValidator.validate(UnusedPathVariableController.class);
        assertEquals(1, unused.size());
        assertTrue(unused.getFirst().contains("getUser(): @PathVariable 'id' does not match any ${...} placeholder in path '/users'"));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(UnresolvedPlaceholderController.class));
        assertThrows(ControllerValidationException.class, () -> client.get(UnusedPathVariableController.class));

        // no false positive on a matching template
        assertTrue(ControllerValidator.validate(ValidPathVariableController.class).isEmpty());
    }
}

