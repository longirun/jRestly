package ru.jrestly;

import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Accept;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.ContentType;
import ru.jrestly.annotation.Cookie;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.annotation.Trace;
import ru.jrestly.http.DownloadProgressListener;
import ru.jrestly.http.RequestType;
import ru.jrestly.http.ResponseHeaders;
import ru.jrestly.http.UploadProgressListener;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit matrix for the shared annotation rules extracted from
 * {@link ControllerValidator}. Pins the exact problem text and prefix shape
 * ({@code Api} for interfaces, {@code get()} for methods) so the public
 * validator behaviour stays byte-for-byte identical.
 */
class AnnotationChecksTest {

    @RequestHeader(name = "")
    @RequestDefaultParam(name = " ", value = "x")
    @Timeout(0)
    @Trace(header = "")
    static interface BlankDeclarations {
    }

    @RequestHeader(name = "X-Trace")
    @RequestDefaultParam(name = "size", value = "10")
    @Timeout(5)
    @Trace(header = "X-Trace")
    static interface ValidDeclarations {
    }

    @Accept({})
    @ContentType(" ")
    @Cookie(name = "", value = "\r\n")
    @OnError(statuses = {200})
    static interface BadMedia {
    }

    @Accept("application/json")
    @ContentType("application/json")
    @Cookie(name = "sid", value = "v")
    @OnError(statuses = {200})
    static interface GoodMedia {
    }

    @OnError(statuses = {404}, errorObject = IllegalStateException.class)
    @OnError(statuses = {404}, errorObject = IllegalArgumentException.class)
    static interface ConflictingOnError {
    }

    static class Controller {
        @Get("/items")
        void annotated(@Cookie(name = "sid\r\n") String sid) {
        }

        @Post(value = "/items", requestType = RequestType.MULTIPART_FORM_DATA)
        void multipartFileWithoutName(@ru.jrestly.annotation.MultipartFormFile(partName = " ") java.io.File file) {
        }

        @Post(value = "/items", requestType = RequestType.MULTIPART_FORM_DATA, params = @RequestDefaultParam(name = "", value = "x"))
        void badVerbDefault() {
        }

        @Get("/items")
        void multipleTargets(@ru.jrestly.annotation.TargetUrl String first,
                             @ru.jrestly.annotation.TargetUrl String second) {
        }

        @Get("/items")
        void bodyOnGet(@ru.jrestly.annotation.RequestBody String body) {
        }

        @Post(value = "/items", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        void formWithoutParams(@ru.jrestly.annotation.PathVariable(name = "id") String id) {
        }

        @Post("/items")
        void sideChannels(@ru.jrestly.annotation.RequestParam(name = "headers") ResponseHeaders headers,
                          @ru.jrestly.annotation.RequestParam(name = "q") UploadProgressListener listener,
                          @ru.jrestly.annotation.RequestParam(name = "download") DownloadProgressListener download) {
        }

        @Post("/items")
        void duplicateSideChannels(ResponseHeaders first, ResponseHeaders second) {
        }

        static void noop() {
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return Controller.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void declarationRulesReportBlankNamesWithPrefix() {
        List<String> problems = new ArrayList<>();
        AnnotationChecks.declarations(BlankDeclarations.class, "BlankDeclarations", problems);
        assertEquals(List.of(
                "BlankDeclarations: @RequestHeader name must not be blank",
                "BlankDeclarations: @RequestDefaultParam name must not be blank"), problems);

        List<String> valid = new ArrayList<>();
        AnnotationChecks.declarations(ValidDeclarations.class, "Valid", valid);
        assertTrue(valid.isEmpty());
    }

    @Test
    void policyRulesReportInvalidTimeoutCompressAndTrace() {
        List<String> problems = new ArrayList<>();
        AnnotationChecks.policies(BlankDeclarations.class, "BlankDeclarations", problems);
        assertEquals(List.of(
                "BlankDeclarations: @Timeout value must be strictly positive, was 0",
                "BlankDeclarations: @Trace header must not be blank"), problems);

        List<String> valid = new ArrayList<>();
        AnnotationChecks.policies(ValidDeclarations.class, "Valid", valid);
        assertTrue(valid.isEmpty());
    }

    @Test
    void compressPolicyIsDelegatedWithTheBarePrefix() throws NoSuchMethodException {
        Method annotated = Holder.class.getDeclaredMethod("compressed");
        List<String> problems = new ArrayList<>();
        AnnotationChecks.policies(annotated, "compressed()", problems);
        assertEquals(List.of("compressed(): @Compress minSizeBytes must not be negative, was -1"), problems);
    }

    @Test
    void mediaRulesCoverEmptyBlankCrlfAndNullFormat() {
        List<String> problems = new ArrayList<>();
        AnnotationChecks.media(BadMedia.class, "BadMedia", problems);
        assertEquals(List.of(
                "BadMedia: @Accept value must not be empty",
                "BadMedia: @ContentType must not be blank",
                "BadMedia: @Cookie name must not be blank",
                "BadMedia: @Cookie value must not contain CRLF characters"), problems);

        List<String> onError = new ArrayList<>();
        java.lang.reflect.AnnotatedElement onlyOnError = OnErrorOnly.class;
        AnnotationChecks.media(onlyOnError, "OnErrorOnly", onError);
        assertEquals(List.of(
                "OnErrorOnly: @OnError status must be a valid HTTP status (100..599), was 99",
                "OnErrorOnly: @OnError status must be a valid HTTP status (100..599), was 600"), onError);

        List<String> valid = new ArrayList<>();
        AnnotationChecks.media(GoodMedia.class, "GoodMedia", valid);
        assertTrue(valid.isEmpty());
    }

    @OnError(statuses = {99, 600})
    static interface OnErrorOnly {
    }

    @Test
    void onErrorReportsConflictingMappingsForTheSameStatus() {
        List<String> problems = new ArrayList<>();
        AnnotationChecks.media(ConflictingOnError.class, "Api", problems);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("Api: @OnError duplicate status 404 mapped to conflicting classes"));
    }

    @Test
    void parameterRulesReportCookieCrlfAndMultipartPartName() {
        List<String> problems = new ArrayList<>();
        Parameter cookie = method("annotated", String.class).getParameters()[0];
        AnnotationChecks.parameter(cookie, RequestType.APPLICATION_JSON, "annotated()", problems);
        assertEquals(List.of("annotated(): @Cookie parameter name must not contain CRLF characters"), problems);

        List<String> multipart = new ArrayList<>();
        Parameter file = method("multipartFileWithoutName", java.io.File.class).getParameters()[0];
        AnnotationChecks.parameter(file, RequestType.MULTIPART_FORM_DATA, "multipartFileWithoutName()", multipart);
        assertEquals(List.of("multipartFileWithoutName(): @MultipartFormFile partName must not be blank"), multipart);
    }

    @Test
    void parameterRulesReportTargetUrlCombinationsAndRequestBodyType() {
        List<String> problems = new ArrayList<>();
        for (Parameter parameter : method("multipleTargets", String.class, String.class).getParameters()) {
            AnnotationChecks.parameter(parameter, RequestType.APPLICATION_JSON, "multipleTargets()", problems);
        }
        assertTrue(problems.isEmpty(), "a well-formed @TargetUrl parameter is valid on its own");

        List<String> body = new ArrayList<>();
        Parameter requestBody = method("bodyOnGet", String.class).getParameters()[0];
        AnnotationChecks.parameter(requestBody, RequestType.APPLICATION_FORM_URLENCODED, "bodyOnGet()", body);
        assertEquals(List.of("bodyOnGet(): @RequestBody parameter requires requestType = APPLICATION_JSON"), body);
    }

    @Test
    void parameterRulesReportSideChannelAnnotations() {
        List<String> problems = new ArrayList<>();
        Parameter[] parameters = method("sideChannels", ResponseHeaders.class, UploadProgressListener.class,
                DownloadProgressListener.class).getParameters();
        AnnotationChecks.parameter(parameters[0], RequestType.APPLICATION_JSON, "sideChannels()", problems);
        AnnotationChecks.parameter(parameters[1], RequestType.APPLICATION_JSON, "sideChannels()", problems);
        AnnotationChecks.parameter(parameters[2], RequestType.APPLICATION_JSON, "sideChannels()", problems);
        assertEquals(3, problems.size());
        assertTrue(problems.stream()
                .anyMatch(p -> p.contains("ResponseHeaders parameter must not be annotated")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("UploadProgressListener parameter must not be annotated")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("DownloadProgressListener parameter must not be annotated")));
        assertFalse(problems.stream().anyMatch(p -> p.contains("@RequestParam format must not be null")));
    }

    @Test
    void parameterCountsReportMultiplicityAndPayloadPresence() {
        List<String> multiple = new ArrayList<>();
        Method targets = method("multipleTargets", String.class, String.class);
        AnnotationChecks.parameterCounts(targets, RequestType.APPLICATION_JSON, "multipleTargets()", multiple);
        assertEquals(List.of("multipleTargets(): multiple parameters annotated with @TargetUrl, at most one is allowed"),
                multiple);

        List<String> getBody = new ArrayList<>();
        Method bodyOnGet = method("bodyOnGet", String.class);
        AnnotationChecks.parameterCounts(bodyOnGet, RequestType.APPLICATION_JSON, "bodyOnGet()", getBody);
        assertEquals(List.of("bodyOnGet(): @Get method must not declare @RequestBody parameter"), getBody);

        List<String> form = new ArrayList<>();
        Method formWithoutParams = method("formWithoutParams", String.class);
        AnnotationChecks.parameterCounts(formWithoutParams, RequestType.APPLICATION_FORM_URLENCODED,
                "formWithoutParams()", form);
        assertEquals(List.of("formWithoutParams(): APPLICATION_FORM_URLENCODED method must have at least one parameter"),
                form);

        List<String> multipart = new ArrayList<>();
        Method duplicateSideChannels = method("duplicateSideChannels", ResponseHeaders.class, ResponseHeaders.class);
        AnnotationChecks.parameterCounts(duplicateSideChannels, RequestType.APPLICATION_JSON,
                "duplicateSideChannels()", multipart);
        assertEquals(List.of(
                "duplicateSideChannels(): multiple parameters of type ResponseHeaders, at most one is allowed"),
                multipart);
    }

    @Test
    void verbDefaultParamsAreValidatedAtMethodLevel() {
        List<String> problems = ControllerValidator.validate(VerbDefaults.class);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("badVerbDefault(): @RequestDefaultParam name must not be blank"));
    }

    static class Holder {
        @Compress(minSizeBytes = -1)
        void compressed() {
        }
    }

    interface VerbDefaults {
        @Post(value = "/items", params = @RequestDefaultParam(name = "", value = "x"))
        void badVerbDefault();
    }
}
