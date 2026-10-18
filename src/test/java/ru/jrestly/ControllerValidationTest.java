package ru.jrestly;

import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Compress;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.fixtures.BrokenController;
import ru.jrestly.fixtures.TestController;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
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
        @ru.jrestly.annotation.Get("/api/stream")
        java.io.InputStream stream();

        @ru.jrestly.annotation.Get("/api/stream/async")
        java.util.concurrent.CompletableFuture<java.io.InputStream> streamAsync();

        @ru.jrestly.annotation.Get("/api/stream/lines")
        java.util.stream.Stream<String> lines();

        @ru.jrestly.annotation.Get("/api/stream/lines/async")
        java.util.concurrent.CompletableFuture<java.util.stream.Stream<String>> linesAsync();

        @ru.jrestly.annotation.Get("/api/stream/events")
        java.util.stream.Stream<ru.jrestly.http.ServerSentEvent> events();

        @ru.jrestly.annotation.Get("/api/stream/events/async")
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
        @ru.jrestly.annotation.Get("/test")
        String call();
    }

    @ru.jrestly.annotation.RequestDefaultParam(name = "", value = "v")
    interface BlankClassDefaultParamController {
        @ru.jrestly.annotation.Get("/test")
        String call();
    }

    interface BlankMethodHeaderAndParamController {
        @ru.jrestly.annotation.Get("/test-header")
        @ru.jrestly.annotation.RequestHeader(name = "", value = "v")
        String blankHeader();

        @ru.jrestly.annotation.Get("/test-param")
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
        @ru.jrestly.annotation.Get("/a")
        @ru.jrestly.annotation.Retry(times = 0)
        String timesZero();

        @ru.jrestly.annotation.Get("/b")
        @ru.jrestly.annotation.Retry(times = 3, initialBackoffMs = 5_000, maxBackoffMs = 100)
        String invertedBackoff();

        @ru.jrestly.annotation.Get("/c")
        @ru.jrestly.annotation.Retry(times = 2, multiplier = 0.5)
        String lowMultiplier();

        @ru.jrestly.annotation.Get("/d")
        @ru.jrestly.annotation.Retry(times = 2, statuses = {999})
        String badStatus();
    }

    interface ValidRetryController {
        @ru.jrestly.annotation.Get("/ok")
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
        @ru.jrestly.annotation.Get("/login")
        @ru.jrestly.annotation.SetAuthDetails(headerName = "")
        String login();
    }

    interface ValidAuthDetailsController {
        @ru.jrestly.annotation.Get("/login")
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
        @ru.jrestly.annotation.Post(value = "/upload",  requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String upload(@ru.jrestly.annotation.MultipartFormFile(partName = "  ") java.io.File file);
    }

    interface ValidMultipartPartNameController {
        @ru.jrestly.annotation.Post(value = "/upload",  requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
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

    interface FileParamOnJsonController {
        @ru.jrestly.annotation.Post("/upload")
        String upload(@ru.jrestly.annotation.MultipartFormFile(partName = "file") java.io.File file);
    }

    interface FileParamOnMultipartController {
        @ru.jrestly.annotation.Post(value = "/upload",  requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String upload(@ru.jrestly.annotation.MultipartFormFile(partName = "file") java.io.File file);
    }

    @Test
    void rejectsMultipartFormFileWithoutMultipartRequestTypeEagerly() {
        // @MultipartFormFile parts are assembled only when the verb's requestType is
        // MULTIPART_FORM_DATA; the inverse guard exists (MULTIPART_FORM_DATA requires
        // a parameter), but a file parameter on a JSON/form method slipped through:
        // the parts list stays null and the arguments are silently dropped from the
        // wire request. It now joins the eager-validation channel (ADR-0024).
        List<String> problems = ControllerValidator.validate(FileParamOnJsonController.class);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("upload(): @MultipartFormFile parameter requires requestType = MULTIPART_FORM_DATA"));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(FileParamOnJsonController.class));

        // no false positive when the verb opts into multipart
        assertTrue(ControllerValidator.validate(FileParamOnMultipartController.class).isEmpty());
    }

    interface BodyParamOnFormController {
        @ru.jrestly.annotation.Post(value = "/submit",  requestType = ru.jrestly.http.RequestType.APPLICATION_FORM_URLENCODED)
        String submit(@ru.jrestly.annotation.RequestBody String payload,
                      @ru.jrestly.annotation.RequestParam(name = "name") String name);
    }

    interface BodyParamOnMultipartController {
        @ru.jrestly.annotation.Post(value = "/upload",  requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String upload(@ru.jrestly.annotation.RequestBody String payload,
                      @ru.jrestly.annotation.MultipartFormFile(partName = "file") java.io.File file);
    }

    interface BodyParamOnJsonController {
        @ru.jrestly.annotation.Post("/submit")
        String submit(@ru.jrestly.annotation.RequestBody String payload);
    }

    @Test
    void rejectsRequestBodyWithoutJsonRequestTypeEagerly() {
        // @RequestBody is serialized by the module's JSON codec and only ever consumed
        // on the APPLICATION_JSON branch of HttpTemplateBuilder; on a form-urlencoded
        // or multipart method the body is assembled from the parameters alone, so the
        // @RequestBody argument is silently dropped from the wire request. The
        // symmetric guard for @MultipartFormFile requires MULTIPART_FORM_DATA
        // (Amendment #214); this completes the payload-annotation/requestType pair
        // (ADR-0035).
        List<String> formProblems = ControllerValidator.validate(BodyParamOnFormController.class);
        assertEquals(1, formProblems.size());
        assertTrue(formProblems.getFirst().contains("submit(): @RequestBody parameter requires requestType = APPLICATION_JSON"));

        List<String> multipartProblems = ControllerValidator.validate(BodyParamOnMultipartController.class);
        assertEquals(1, multipartProblems.size());
        assertTrue(multipartProblems.getFirst().contains("upload(): @RequestBody parameter requires requestType = APPLICATION_JSON"));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(BodyParamOnFormController.class));

        // no false positive on a JSON method (the default requestType)
        assertTrue(ControllerValidator.validate(BodyParamOnJsonController.class).isEmpty());
    }

    interface FileAndRequestParamOnSameParamController {
        @ru.jrestly.annotation.Post(value = "/upload",  requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String upload(@ru.jrestly.annotation.MultipartFormFile(partName = "file")
                      @ru.jrestly.annotation.RequestParam(name = "desc") String value);
    }

    interface ValidMultipartFieldAndFileController {
        @ru.jrestly.annotation.Post(value = "/upload",  requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String upload(@ru.jrestly.annotation.RequestParam(name = "desc") String description,
                      @ru.jrestly.annotation.MultipartFormFile(partName = "file") java.io.File file);
    }

    @Test
    void rejectsMultipartFormFileCombinedWithRequestParamEagerly() {
        // createMultipartParts() resolves a parameter with an if/else-if chain: a
        // @MultipartFormFile wins, so a @RequestParam declared on the same parameter
        // is never turned into a part. Multipart methods do not build a query string
        // either (HttpTemplateBuilder skips createRequestParams for MULTIPART_FORM_DATA),
        // so the @RequestParam argument is silently dropped from the wire request.
        // Unlike the other pairings among @PathVariable/@RequestParam/@RequestHeader/
        // @MultipartFormFile (which double-bind), this one is a silent drop, so the
        // combination joins the eager-validation channel (ADR-0024).
        List<String> problems = ControllerValidator.validate(FileAndRequestParamOnSameParamController.class);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains(
                "upload(): parameter must not combine @MultipartFormFile with @RequestParam"));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class,
                () -> client.get(FileAndRequestParamOnSameParamController.class));

        // no false positive on separate file and text-field parameters
        assertTrue(ControllerValidator.validate(ValidMultipartFieldAndFileController.class).isEmpty());
    }

    interface PathOnlyFormController {
        @ru.jrestly.annotation.Post(value = "/users/{id}",  requestType = ru.jrestly.http.RequestType.APPLICATION_FORM_URLENCODED)
        String postForm(@ru.jrestly.annotation.PathVariable(name = "id") String id);
    }

    interface PathOnlyMultipartController {
        @ru.jrestly.annotation.Post(value = "/users/{id}",  requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String postMultipart(@ru.jrestly.annotation.PathVariable(name = "id") String id);
    }

    interface PathAndFormParamController {
        @ru.jrestly.annotation.Post(value = "/users/{id}",  requestType = ru.jrestly.http.RequestType.APPLICATION_FORM_URLENCODED)
        String postForm(@ru.jrestly.annotation.PathVariable(name = "id") String id,
                        @ru.jrestly.annotation.RequestParam(name = "name") String name);
    }

    interface PathAndMultipartParamController {
        @ru.jrestly.annotation.Post(value = "/users/{id}",  requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String postMultipart(@ru.jrestly.annotation.PathVariable(name = "id") String id,
                             @ru.jrestly.annotation.MultipartFormFile(partName = "file") java.io.File file);
    }

    @Test
    void rejectsFormOrMultipartWithoutBodyParametersEagerly() {
        // @PathVariable substitutes template variables into the URL path; it does
        // not construct form-urlencoded or multipart request bodies. When a method
        // declared APPLICATION_FORM_URLENCODED or MULTIPART_FORM_DATA has only
        // @PathVariable parameters, HttpTemplateBuilder assembles an empty body
        // (zero form params or zero multipart parts) and sends an empty body on the wire.
        // ControllerValidator's payloadParamCount previously neglected @PathVariable,
        // so such methods silently passed validation (ADR-0024).
        List<String> formProblems = ControllerValidator.validate(PathOnlyFormController.class);
        assertEquals(1, formProblems.size());
        assertTrue(formProblems.getFirst().contains("postForm(): APPLICATION_FORM_URLENCODED method must have at least one parameter"));

        List<String> multipartProblems = ControllerValidator.validate(PathOnlyMultipartController.class);
        assertEquals(1, multipartProblems.size());
        assertTrue(multipartProblems.getFirst().contains("postMultipart(): MULTIPART_FORM_DATA method must have at least one parameter"));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(PathOnlyFormController.class));
        assertThrows(ControllerValidationException.class, () -> client.get(PathOnlyMultipartController.class));

        // no false positive when body parameters accompany @PathVariable
        assertTrue(ControllerValidator.validate(PathAndFormParamController.class).isEmpty());
        assertTrue(ControllerValidator.validate(PathAndMultipartParamController.class).isEmpty());
    }

    interface InvalidExpectStatusController {
        @ru.jrestly.annotation.Get("/empty")
        @ru.jrestly.annotation.ExpectStatus(statuses = {})
        String emptyStatuses();

        @ru.jrestly.annotation.Get("/low")
        @ru.jrestly.annotation.ExpectStatus(statuses = {99})
        String lowStatus();

        @ru.jrestly.annotation.Get("/high")
        @ru.jrestly.annotation.ExpectStatus(statuses = {600})
        String highStatus();
    }

    interface ValidExpectStatusController {
        @ru.jrestly.annotation.Get("/ok")
        @ru.jrestly.annotation.ExpectStatus(statuses = {200, 201})
        String ok();

        @ru.jrestly.annotation.Get("/accepted")
        @ru.jrestly.annotation.ExpectStatus(statuses = {202})
        String accepted();
    }

    @Test
    void rejectsInvalidExpectStatusConfigurationEagerly() {
        // @ExpectStatus whitelists successful HTTP status codes. An empty array silently
        // turns any response (even 200 OK) into an UnexpectedStatusException because no
        // status matches. Statuses outside [100, 599] are invalid HTTP codes.
        List<String> problems = ControllerValidator.validate(InvalidExpectStatusController.class);
        assertEquals(3, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.contains("emptyStatuses(): @ExpectStatus statuses must not be empty")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("lowStatus(): @ExpectStatus status must be in [100, 599], was 99")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("highStatus(): @ExpectStatus status must be in [100, 599], was 600")));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(InvalidExpectStatusController.class));

        // no false positive on valid statuses
        assertTrue(ControllerValidator.validate(ValidExpectStatusController.class).isEmpty());
    }

    interface InvalidFollowRedirectsController {
        @ru.jrestly.annotation.Get("/neg")
        @ru.jrestly.annotation.FollowRedirects(count = -1)
        String negativeCount();

        @ru.jrestly.annotation.Get("/neg5")
        @ru.jrestly.annotation.FollowRedirects(count = -5)
        String negativeFive();
    }

    interface ValidFollowRedirectsController {
        @ru.jrestly.annotation.Get("/zero")
        @ru.jrestly.annotation.FollowRedirects(count = 0)
        String zeroCount();

        @ru.jrestly.annotation.Get("/hops")
        @ru.jrestly.annotation.FollowRedirects(count = 3)
        String positiveCount();
    }

    @Test
    void rejectsInvalidFollowRedirectsConfigurationEagerly() {
        // @FollowRedirects bounds the number of redirect hops. Negative hop counts are invalid
        // and cause spurious "Too many redirects" logs when 3xx responses arrive.
        List<String> problems = ControllerValidator.validate(InvalidFollowRedirectsController.class);
        assertEquals(2, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.contains("negativeCount(): @FollowRedirects count must not be negative, was -1")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("negativeFive(): @FollowRedirects count must not be negative, was -5")));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(InvalidFollowRedirectsController.class));

        // no false positive on 0 or positive hop counts
        assertTrue(ControllerValidator.validate(ValidFollowRedirectsController.class).isEmpty());
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
        @ru.jrestly.annotation.Get("/users/{id}")
        String getUser();
    }

    interface UnusedPathVariableController {
        @ru.jrestly.annotation.Get("/users")
        String getUser(@ru.jrestly.annotation.PathVariable(name = "id") String id);
    }

    interface ValidPathVariableController {
        @ru.jrestly.annotation.Get("/users/{id}")
        String getUser(@ru.jrestly.annotation.PathVariable(name = "id") String id);
    }

    interface BraceStylePathVariableController {
        @ru.jrestly.annotation.Get("/users/{id}")
        String getUser(@ru.jrestly.annotation.PathVariable String id);
    }

    @Test
    void acceptsBraceStylePathPlaceholdersEagerly() {
        // ADR-0067 P3: the industry-standard {id} spelling (Retrofit/Feign/Spring/
        // RFC 6570) is the native placeholder syntax.
        List<String> problems = ControllerValidator.validate(BraceStylePathVariableController.class);
        assertTrue(problems.isEmpty(), "Brace-style placeholders should be legal, but got: " + problems);
    }

    @Test
    void rejectsPathVariableTemplateMismatchEagerly() {
        // ADR-0025: the path template and its @PathVariable parameters must agree.
        // An unresolved {id} placeholder is only rejected at send time by
        // URI.create ("Illegal character in path"), and a @PathVariable with no
        // matching placeholder is silently dropped from the URL. Both now join the
        // eager-validation channel.
        List<String> unresolved = ControllerValidator.validate(UnresolvedPlaceholderController.class);
        assertEquals(1, unresolved.size());
        assertTrue(unresolved.getFirst().contains("getUser(): path placeholder '{id}' has no matching @PathVariable parameter"));

        List<String> unused = ControllerValidator.validate(UnusedPathVariableController.class);
        assertEquals(1, unused.size());
        assertTrue(unused.getFirst().contains("getUser(): @PathVariable 'id' does not match any {...} placeholder in path '/users'"));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(UnresolvedPlaceholderController.class));
        assertThrows(ControllerValidationException.class, () -> client.get(UnusedPathVariableController.class));

        // no false positive on a matching template
        assertTrue(ControllerValidator.validate(ValidPathVariableController.class).isEmpty());
    }

    interface InvalidPathController {
        @ru.jrestly.annotation.Get("/api/ us")
        String space();

        @ru.jrestly.annotation.Get("/api/users/{id")
        String unterminatedBrace();

        @ru.jrestly.annotation.Get("/api/100%")
        String malformedEscape();
    }

    interface ValidPathCharactersController {
        @ru.jrestly.annotation.Get("/api/items%20list?x=1")
        String percentEncoded();

        @ru.jrestly.annotation.Get("http://[::1]/api/items")
        String ipv6AbsoluteUrl();
    }

    @Test
    void rejectsInvalidUriCharactersInPathEagerly() {
        // ADR-0025: a verb's path is another paired declarative construct. A path
        // (or absolute URL) carrying a character that is illegal in a URI -- a
        // space, a CR/LF, an unterminated {...} placeholder run, or a malformed
        // escape pair -- is only rejected at send time by URI.create ("Illegal
        // character in path at index N" / "Malformed escape pair"), naming neither
        // the method nor the path. It now joins the eager-validation channel.
        List<String> problems = ControllerValidator.validate(InvalidPathController.class);
        assertEquals(3, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("space(): path must be a valid URI reference")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("unterminatedBrace(): path must be a valid URI reference")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("malformedEscape(): path must be a valid URI reference")));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(InvalidPathController.class));

        // no false positive on legal path characters, including {...} placeholders
        // and IPv6 brackets (legal in the authority of an absolute URL)
        assertTrue(ControllerValidator.validate(ValidPathCharactersController.class).isEmpty());
        assertTrue(ControllerValidator.validate(ValidPathVariableController.class).isEmpty());
    }

    @Test
    void rejectsParameterNameFallbackWithoutParametersFlagEagerly() throws Exception {
        // ADR-0025 / README: a blank name() falls back to the Java parameter name,
        // which only exists under the '-parameters' compiler flag. Without it the
        // fallback silently becomes "arg0" -- a query/header/cookie goes out under
        // the wrong name (the server ignores it) and a @PathVariable never matches
        // its placeholder. The interface is compiled at runtime both ways to prove
        // the guard keys on the compiler flag, not on the declarations themselves.
        String bindings = """
                    @Get("/search")
                    String search(@RequestParam String query);

                    @Get("/api")
                    String fetch(@RequestHeader String token);

                    @Get("/session")
                    String session(@Cookie String sid);
                """;

        Class<?> withoutFlag = compileController("NoParamsController", bindings, false);
        List<String> problems = ControllerValidator.validate(withoutFlag);
        assertEquals(3, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("search(): @RequestParam requires an explicit name()")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("fetch(): @RequestHeader requires an explicit name()")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("session(): @Cookie requires an explicit name()")));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(withoutFlag));

        // the very same declarations are valid once the flag retains parameter names
        assertTrue(ControllerValidator.validate(compileController("ParamsController", bindings, true)).isEmpty());

        // a Map parameter takes its names from the entries, not the parameter name
        assertTrue(ControllerValidator.validate(compileController("MapController", """
                    @Get("/search")
                    String search(@RequestParam java.util.Map<String, String> filters);
                """, false)).isEmpty());

        // an explicit name() removes the dependency on the flag entirely
        assertTrue(ControllerValidator.validate(compileController("ExplicitNameController", """
                    @Get("/search")
                    String search(@RequestParam(name = "query") String q);
                """, false)).isEmpty());
    }

    @Test
    void reportsUnnamedPathVariableWithoutParametersFlagActionably() throws Exception {
        Class<?> controller = compileController("NoParamsPathVarController", """
                    @Get("/items/{id}")
                    String item(@PathVariable String id);
                """, false);
        List<String> problems = ControllerValidator.validate(controller);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("item(): @PathVariable requires an explicit name()")));
        // the synthetic fallback name is not echoed as a confusing mismatch
        assertTrue(problems.stream().noneMatch(p -> p.contains("'arg0'")));
    }

    interface EmptyPlaceholderController {
        @ru.jrestly.annotation.Get("/users/{}")
        String emptyPlaceholder();
    }

    interface InvalidCircuitBreakerController {
        @ru.jrestly.annotation.Get("/cb")
        @ru.jrestly.annotation.CircuitBreaker(name = "cb", minimumNumberOfCalls = 10, slidingWindowSize = 5, failureRateThreshold = 105.0f)
        String brokenCb();
    }

    interface NegativeRetryBackoffController {
        @ru.jrestly.annotation.Get("/retry")
        @ru.jrestly.annotation.Retry(initialBackoffMs = -10, maxBackoffMs = -5)
        String brokenRetry();
    }

    @Test
    void rejectsEmptyPlaceholderAndInvalidCircuitBreakerAndRetry() {
        List<String> placeholderProblems = ControllerValidator.validate(EmptyPlaceholderController.class);
        assertEquals(1, placeholderProblems.size());
        assertTrue(placeholderProblems.getFirst().contains("path placeholder '{}' must not be blank"));

        List<String> cbProblems = ControllerValidator.validate(InvalidCircuitBreakerController.class);
        assertTrue(cbProblems.stream().anyMatch(p -> p.contains("failureRateThreshold must be in (0.0, 100.0]")));
        assertTrue(cbProblems.stream().anyMatch(p -> p.contains("slidingWindowSize (5) must be >= minimumNumberOfCalls (10)")));

        List<String> retryProblems = ControllerValidator.validate(NegativeRetryBackoffController.class);
        assertTrue(retryProblems.stream().anyMatch(p -> p.contains("initialBackoffMs must not be negative")));
        assertTrue(retryProblems.stream().anyMatch(p -> p.contains("maxBackoffMs must not be negative")));
    }

    @Test
    void validateToleratesNullAndNonInterfaceClass() {
        assertTrue(ControllerValidator.validate(null).isEmpty());
        assertTrue(ControllerValidator.validate(String.class).isEmpty());
    }

    interface DiamondBase {
        @ru.jrestly.annotation.Get("/base")
        String base();
    }
    interface DiamondLeft extends DiamondBase {}
    interface DiamondRight extends DiamondBase {}
    interface DiamondTop extends DiamondLeft, DiamondRight {
        static void helper() {}
    }

    @Test
    void diamondInheritanceAndStaticMethodsPassValidation() {
        assertTrue(ControllerValidator.validate(DiamondTop.class).isEmpty());
    }

    @ru.jrestly.annotation.Accept("application/json\n")
    @ru.jrestly.annotation.ContentType("text/plain\n")
    @ru.jrestly.annotation.Cookie(name = "c_name\n", value = "c_val\n")
    interface ClassLevelLfController {
        @ru.jrestly.annotation.Get("/lf")
        @ru.jrestly.annotation.Accept("text/html\n")
        @ru.jrestly.annotation.ContentType("application/xml\n")
        @ru.jrestly.annotation.Cookie(name = "m_name\n", value = "m_val\n")
        String lfMethod(@ru.jrestly.annotation.Cookie(name = "p_name\n") String cookieParam);
    }

    @Test
    void rejectsCrlfInAnnotationsWithPureLf() {
        List<String> problems = ControllerValidator.validate(ClassLevelLfController.class);
        assertEquals(9, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Accept media type must not contain CRLF characters")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("@ContentType must not contain CRLF characters")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Cookie name must not contain CRLF characters")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Cookie value must not contain CRLF characters")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("@Cookie parameter name must not contain CRLF characters")));
    }

    @ru.jrestly.annotation.OnError(statuses = {600, 404}, errorObject = ru.jrestly.problem.ProblemDetail.class)
    @ru.jrestly.annotation.OnError(statuses = {404}, errorObject = ru.jrestly.problem.ProblemDetail.class)
    interface OnErrorClassEdgeCasesController {
        @ru.jrestly.annotation.Get("/err")
        @ru.jrestly.annotation.OnError(statuses = {600, 404}, errorObject = ru.jrestly.problem.ProblemDetail.class)
        @ru.jrestly.annotation.OnError(statuses = {404}, errorObject = ru.jrestly.problem.ProblemDetail.class)
        String call();
    }

    @Test
    void reportsOnErrorStatusAbove599AndToleratesDuplicateStatusWithSameErrorObject() {
        List<String> problems = ControllerValidator.validate(OnErrorClassEdgeCasesController.class);
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("must be a valid HTTP status (100..599), was 600"));
        assertTrue(problems.get(1).contains("must be a valid HTTP status (100..599), was 600"));
    }

    interface DeleteWithParamsController {
        @ru.jrestly.annotation.Delete(value = "/delete-item",  params = {
            @ru.jrestly.annotation.RequestDefaultParam(name = "force", value = "true"),
            @ru.jrestly.annotation.RequestDefaultParam(name = "   ", value = "blank")
        })
        String deleteItem();
    }

    @Test
    void resolvesDeletePathAndValidatesVerbDefaultParams() {
        List<String> problems = ControllerValidator.validate(DeleteWithParamsController.class);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("@RequestDefaultParam name must not be blank"));
    }

    interface ParameterAnnotationCombinationController {
        @ru.jrestly.annotation.Post("/combo-body-part")
        String bodyWithPart(@ru.jrestly.annotation.RequestBody @ru.jrestly.annotation.MultipartFormFile(partName = "f") byte[] payload);

        @ru.jrestly.annotation.Get("/combo-cookie")
        String cookieWithOthers(
            @ru.jrestly.annotation.Cookie(name = "c2") @ru.jrestly.annotation.RequestParam String c2,
            @ru.jrestly.annotation.Cookie(name = "c3") @ru.jrestly.annotation.PathVariable(name = "c3") String c3,
            @ru.jrestly.annotation.Cookie(name = "c4") @ru.jrestly.annotation.RequestBody String c4,
            @ru.jrestly.annotation.Cookie(name = "c5") @ru.jrestly.annotation.MultipartFormFile(partName = "p") byte[] c5
        );

        @ru.jrestly.annotation.Get("/combo-target-url")
        String targetUrlWithOthers(
            @ru.jrestly.annotation.TargetUrl @ru.jrestly.annotation.RequestParam String u1,
            @ru.jrestly.annotation.TargetUrl @ru.jrestly.annotation.PathVariable(name = "u2") String u2,
            @ru.jrestly.annotation.TargetUrl @ru.jrestly.annotation.RequestHeader(name = "H") String u3,
            @ru.jrestly.annotation.TargetUrl @ru.jrestly.annotation.Cookie(name = "C") String u4,
            @ru.jrestly.annotation.TargetUrl @ru.jrestly.annotation.RequestBody String u5,
            @ru.jrestly.annotation.TargetUrl @ru.jrestly.annotation.MultipartFormFile(partName = "u6") byte[] u6
        );

        @ru.jrestly.annotation.Get("/combo-headers-progress")
        String progressAndHeaders(
            @ru.jrestly.annotation.TargetUrl ru.jrestly.http.ResponseHeaders headersWithTargetUrl,
            @ru.jrestly.annotation.RequestParam ru.jrestly.http.UploadProgressListener uploadWithParam,
            @ru.jrestly.annotation.PathVariable(name = "up") ru.jrestly.http.UploadProgressListener uploadWithVar,
            @ru.jrestly.annotation.RequestHeader(name = "UH") ru.jrestly.http.UploadProgressListener uploadWithHeader,
            @ru.jrestly.annotation.Cookie(name = "UC") ru.jrestly.http.UploadProgressListener uploadWithCookie,
            @ru.jrestly.annotation.RequestBody ru.jrestly.http.UploadProgressListener uploadWithBody,
            @ru.jrestly.annotation.MultipartFormFile(partName = "UF") ru.jrestly.http.UploadProgressListener uploadWithPart,
            @ru.jrestly.annotation.TargetUrl ru.jrestly.http.UploadProgressListener uploadWithTarget,
            @ru.jrestly.annotation.RequestParam ru.jrestly.http.DownloadProgressListener downloadWithParam,
            @ru.jrestly.annotation.PathVariable(name = "dp") ru.jrestly.http.DownloadProgressListener downloadWithVar,
            @ru.jrestly.annotation.RequestHeader(name = "DH") ru.jrestly.http.DownloadProgressListener downloadWithHeader,
            @ru.jrestly.annotation.Cookie(name = "DC") ru.jrestly.http.DownloadProgressListener downloadWithCookie,
            @ru.jrestly.annotation.RequestBody ru.jrestly.http.DownloadProgressListener downloadWithBody,
            @ru.jrestly.annotation.MultipartFormFile(partName = "DF") ru.jrestly.http.DownloadProgressListener downloadWithPart,
            @ru.jrestly.annotation.TargetUrl ru.jrestly.http.DownloadProgressListener downloadWithTarget
        );
    }

    @Test
    void rejectsDisallowedCombinationsOfParameterAnnotations() {
        List<String> problems = ControllerValidator.validate(ParameterAnnotationCombinationController.class);
        assertTrue(problems.stream().anyMatch(p -> p.contains("parameter must not combine @RequestBody with other HTTP parameter annotations")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("parameter must not combine @Cookie with other HTTP parameter annotations")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("parameter must not combine @TargetUrl with other HTTP parameter annotations")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("ResponseHeaders parameter must not be annotated with HTTP parameter annotations")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("UploadProgressListener parameter must not be annotated with HTTP parameter annotations")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("DownloadProgressListener parameter must not be annotated with HTTP parameter annotations")));
    }

    abstract static class AbstractCustomInterceptor implements ru.jrestly.interceptor.RequestInterceptor {}
    abstract static class AbstractCustomMetrics implements ru.jrestly.metrics.ClientMetricsListener {}

    interface AbstractInterceptorAndMetricsController {
        @ru.jrestly.annotation.Get("/abs")
        @ru.jrestly.annotation.WithInterceptor(AbstractCustomInterceptor.class)
        @ru.jrestly.annotation.WithMetrics(AbstractCustomMetrics.class)
        String call();
    }

    @Test
    void rejectsAbstractClassesInWithInterceptorAndWithMetrics() {
        List<String> problems = ControllerValidator.validate(AbstractInterceptorAndMetricsController.class);
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("@WithInterceptor class AbstractCustomInterceptor must not be abstract or an interface"));
        assertTrue(problems.get(1).contains("@WithMetrics class AbstractCustomMetrics must not be abstract or an interface"));
    }

    interface NonAnnotatedMethodHolder {
        void unannotatedMethod();
    }

    @Test
    void packagePrivateSeamsHandleNullsAndEdgeCases() throws Exception {
        List<String> problems = new ArrayList<>();
        ControllerValidator.validateInterceptorClass(null, "Test", problems);
        assertEquals(List.of("Test: @WithInterceptor class must not be null"), problems);

        problems.clear();
        ControllerValidator.validateMetricsClass(null, "Test", problems);
        assertEquals(List.of("Test: @WithMetrics class must not be null"), problems);

        problems.clear();
        ControllerValidator.validatePath("Test", null, problems);
        assertTrue(problems.isEmpty());

        java.lang.reflect.Method nonAnnotated = NonAnnotatedMethodHolder.class.getMethod("unannotatedMethod");
        ControllerValidator.validatePathVariableMismatch(nonAnnotated, problems);
        assertTrue(problems.isEmpty());
        org.junit.jupiter.api.Assertions.assertNull(ControllerValidator.resolvePath(nonAnnotated));
        assertEquals(0, ControllerValidator.resolveVerbDefaultParams(nonAnnotated).length);

        ru.jrestly.annotation.Retry retryProxy = (ru.jrestly.annotation.Retry) Proxy.newProxyInstance(
                ru.jrestly.annotation.Retry.class.getClassLoader(),
                new Class<?>[]{ru.jrestly.annotation.Retry.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "times" -> 3;
                    case "initialBackoffMs" -> 100L;
                    case "maxBackoffMs" -> 500L;
                    case "multiplier" -> Double.NaN;
                    case "statuses" -> new int[]{600};
                    case "annotationType" -> ru.jrestly.annotation.Retry.class;
                    default -> "proxy @Retry";
                });
        problems.clear();
        ControllerValidator.validateRetry("Test", retryProxy, problems);
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("@Retry multiplier must be >= 1.0, was NaN"));
        assertTrue(problems.get(1).contains("@Retry status must be in [100, 599], was 600"));

        ru.jrestly.annotation.ExpectStatus expectStatusProxy = (ru.jrestly.annotation.ExpectStatus) Proxy.newProxyInstance(
                ru.jrestly.annotation.ExpectStatus.class.getClassLoader(),
                new Class<?>[]{ru.jrestly.annotation.ExpectStatus.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statuses" -> new int[]{600};
                    case "annotationType" -> ru.jrestly.annotation.ExpectStatus.class;
                    default -> "proxy @ExpectStatus";
                });
        problems.clear();
        ControllerValidator.validateExpectStatus("Test", expectStatusProxy, problems);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("@ExpectStatus status must be in [100, 599], was 600"));
    }



    // built with -parameters, so a class that lacks the MethodParameters attribute
    // -- the only way to reproduce the missing flag -- must be produced here. A
    // URLClassLoader stays open for the test's lifetime so the proxy can be built.
    private static Class<?> compileController(String simpleName, String body, boolean parameters) throws Exception {
        Path dir = Files.createTempDirectory("jrestly-ctrl-" + simpleName);
        Path source = dir.resolve(simpleName + ".java");
        Files.writeString(source, """
                package ru.jrestly.gen;

                import ru.jrestly.annotation.Get;
                import ru.jrestly.annotation.RequestParam;
                import ru.jrestly.annotation.RequestHeader;
                import ru.jrestly.annotation.Cookie;
                import ru.jrestly.annotation.PathVariable;

                public interface %s {
                %s
                }
                """.formatted(simpleName, body));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the JDK compiler is required for this test");
        List<String> options = new ArrayList<>(List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-d", dir.toString()));
        if (parameters) {
            options.add("-parameters");
        }
        options.add(source.toString());
        assertEquals(0, compiler.run(null, null, null, options.toArray(String[]::new)));

        URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                ControllerValidationTest.class.getClassLoader());
        return Class.forName("ru.jrestly.gen." + simpleName, false, loader);
    }
}

