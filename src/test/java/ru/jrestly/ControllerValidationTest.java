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

    interface FileParamOnJsonController {
        @ru.jrestly.annotation.Post(path = "/upload")
        String upload(@ru.jrestly.annotation.MultipartFormFile(partName = "file") java.io.File file);
    }

    interface FileParamOnMultipartController {
        @ru.jrestly.annotation.Post(path = "/upload", requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
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
        @ru.jrestly.annotation.Post(path = "/submit", requestType = ru.jrestly.http.RequestType.APPLICATION_FORM_URLENCODED)
        String submit(@ru.jrestly.annotation.RequestBody String payload,
                      @ru.jrestly.annotation.RequestParam(name = "name") String name);
    }

    interface BodyParamOnMultipartController {
        @ru.jrestly.annotation.Post(path = "/upload", requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String upload(@ru.jrestly.annotation.RequestBody String payload,
                      @ru.jrestly.annotation.MultipartFormFile(partName = "file") java.io.File file);
    }

    interface BodyParamOnJsonController {
        @ru.jrestly.annotation.Post(path = "/submit")
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
        @ru.jrestly.annotation.Post(path = "/upload", requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String upload(@ru.jrestly.annotation.MultipartFormFile(partName = "file")
                      @ru.jrestly.annotation.RequestParam(name = "desc") String value);
    }

    interface ValidMultipartFieldAndFileController {
        @ru.jrestly.annotation.Post(path = "/upload", requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
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
        @ru.jrestly.annotation.Post(path = "/users/${id}", requestType = ru.jrestly.http.RequestType.APPLICATION_FORM_URLENCODED)
        String postForm(@ru.jrestly.annotation.PathVariable(name = "id") String id);
    }

    interface PathOnlyMultipartController {
        @ru.jrestly.annotation.Post(path = "/users/${id}", requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
        String postMultipart(@ru.jrestly.annotation.PathVariable(name = "id") String id);
    }

    interface PathAndFormParamController {
        @ru.jrestly.annotation.Post(path = "/users/${id}", requestType = ru.jrestly.http.RequestType.APPLICATION_FORM_URLENCODED)
        String postForm(@ru.jrestly.annotation.PathVariable(name = "id") String id,
                        @ru.jrestly.annotation.RequestParam(name = "name") String name);
    }

    interface PathAndMultipartParamController {
        @ru.jrestly.annotation.Post(path = "/users/${id}", requestType = ru.jrestly.http.RequestType.MULTIPART_FORM_DATA)
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

    interface InvalidPathController {
        @ru.jrestly.annotation.Get(path = "/api/ us")
        String space();

        @ru.jrestly.annotation.Get(path = "/api/users/{id}")
        String singleBrace();

        @ru.jrestly.annotation.Get(path = "/api/100%")
        String malformedEscape();
    }

    interface ValidPathCharactersController {
        @ru.jrestly.annotation.Get(path = "/api/items%20list?x=1")
        String percentEncoded();

        @ru.jrestly.annotation.Get(path = "http://[::1]/api/items")
        String ipv6AbsoluteUrl();
    }

    @Test
    void rejectsInvalidUriCharactersInPathEagerly() {
        // ADR-0025: a verb's path is another paired declarative construct. A path
        // (or absolute URL) carrying a character that is illegal in a URI -- a
        // space, a CR/LF, or a Retrofit/JAX-RS-style {id} placeholder instead of
        // ${id} -- is only rejected at send time by URI.create ("Illegal character
        // in path at index N" / "Malformed escape pair"), naming neither the method
        // nor the path. It now joins the eager-validation channel.
        List<String> problems = ControllerValidator.validate(InvalidPathController.class);
        assertEquals(3, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("space(): path must be a valid URI reference")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("singleBrace(): path must be a valid URI reference")));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("malformedEscape(): path must be a valid URI reference")));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(InvalidPathController.class));

        // no false positive on legal path characters, including ${...} placeholders
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
                    @Get(path = "/search")
                    String search(@RequestParam String query);

                    @Get(path = "/api")
                    String fetch(@RequestHeader String token);

                    @Get(path = "/session")
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
                    @Get(path = "/search")
                    String search(@RequestParam java.util.Map<String, String> filters);
                """, false)).isEmpty());

        // an explicit name() removes the dependency on the flag entirely
        assertTrue(ControllerValidator.validate(compileController("ExplicitNameController", """
                    @Get(path = "/search")
                    String search(@RequestParam(name = "query") String q);
                """, false)).isEmpty());
    }

    @Test
    void reportsUnnamedPathVariableWithoutParametersFlagActionably() throws Exception {
        Class<?> controller = compileController("NoParamsPathVarController", """
                    @Get(path = "/items/${id}")
                    String item(@PathVariable String id);
                """, false);
        List<String> problems = ControllerValidator.validate(controller);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("item(): @PathVariable requires an explicit name()")));
        // the synthetic fallback name is not echoed as a confusing mismatch
        assertTrue(problems.stream().noneMatch(p -> p.contains("'arg0'")));
    }

    // Compiles a throwaway controller interface at runtime. The project itself is
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

