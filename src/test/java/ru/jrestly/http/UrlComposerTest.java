package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.TargetUrl;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link UrlComposer} — the URI-assembly slice extracted from
 * {@code HttpTemplateBuilder.createUrl}/{@code HttpTemplate.createUri}
 * (ADR-0082). Pins the builder-side composition (base URL, path variables,
 * {@code @TargetUrl}, static path query) and the template-side query
 * resolution, independent of network. No network.
 */
class UrlComposerTest {

    interface PlainApi {
        @Get("/users")
        String users();

        @Get("/users/{id}")
        String user(@PathVariable String id);

        @Get("/users/{id}")
        String rawUser(@PathVariable(encoded = true) String id);

        @Get("/users/{id}")
        String search(@RequestParam(name = "q") String q, @PathVariable String id);

        @Get("/items?size=10")
        String items();

        @Get("/items?size=10")
        String itemsAt(@TargetUrl URI target);

        @Get("/users")
        String usersAt(@TargetUrl URI target);

        @Get
        String at(@TargetUrl URI target);

        @Get("https://cdn.example.com/absolute")
        String absolute(@TargetUrl URI target);

        @Get("http://cdn.example.com/plain")
        String httpAbsolute(@TargetUrl URI target);

        @Get("http://plain.example.com/x")
        String plainAbsolute();
    }

    private static MethodMetadata metadata(String methodName) throws Exception {
        Class<?>[] parameterTypes = switch (methodName) {
            case "user", "rawUser" -> new Class<?>[]{String.class};
            case "search" -> new Class<?>[]{String.class, String.class};
            case "users", "items", "plainAbsolute" -> new Class<?>[0];
            default -> new Class<?>[]{URI.class};
        };
        Method method = PlainApi.class.getDeclaredMethod(methodName, parameterTypes);
        return MethodMetadata.of(method, PlainApi.class);
    }

    private static String compose(String methodName, Object... args) throws Exception {
        MethodMetadata metadata = metadata(methodName);
        return UrlComposer.compose(metadata.requestMeta(), metadata, args, "https://api.example.com");
    }

    @Test
    @DisplayName("compose joins the base URL and a relative path across slash permutations")
    void composeJoinsBaseAndPath() throws Exception {
        assertEquals("https://api.example.com/users", compose("users"));
        assertEquals("https://api.example.com/users", UrlComposer.joinUrlPath("https://api.example.com/", "/users"));
        assertEquals("https://api.example.com/users", UrlComposer.joinUrlPath("https://api.example.com/", "users"));
        assertEquals("https://api.example.com/users", UrlComposer.joinUrlPath("https://api.example.com", "/users"));
        assertEquals("https://api.example.com/users", UrlComposer.joinUrlPath("https://api.example.com", "users"));
    }

    @Test
    @DisplayName("compose substitutes and percent-encodes path variables, honoring an encoded declaration")
    void composeSubstitutesPathVariables() throws Exception {
        assertEquals("https://api.example.com/users/John%20Doe", compose("user", "John Doe"));
        assertEquals("https://api.example.com/users/a/b", compose("rawUser", "a/b"));
    }

    @Test
    @DisplayName("compose ignores non-path-variable descriptors when substituting")
    void composeSkipsNonPathVariableDescriptors() throws Exception {
        assertEquals("https://api.example.com/users/42", compose("search", "term", "42"));
    }

    @Test
    @DisplayName("compose tolerates absent arguments: placeholders stay and no target is applied")
    void composeToleratesAbsentArguments() throws Exception {
        assertEquals("https://api.example.com/users/{id}", compose("user"));

        MethodMetadata user = metadata("user");
        assertEquals("https://api.example.com/users/{id}", UrlComposer.compose(
                user.requestMeta(), user, null, "https://api.example.com"));

        MethodMetadata at = metadata("at");
        assertEquals("https://api.example.com", UrlComposer.compose(
                at.requestMeta(), at, null, "https://api.example.com"));
        assertEquals("https://api.example.com", UrlComposer.compose(
                at.requestMeta(), at, new Object[0], "https://api.example.com"));
    }

    @Test
    @DisplayName("compose returns the target as-is when the verb path is null")
    void composeHandlesNullPathWithTargetUrl() throws Exception {
        MethodMetadata at = metadata("at");
        RequestMeta nullPath = new RequestMeta(HttpMethod.GET, null, RequestType.APPLICATION_JSON, null);
        assertEquals("https://s3.example.com/bucket/key", UrlComposer.compose(
                nullPath, at, new Object[]{URI.create("https://s3.example.com/bucket/key")},
                "https://api.example.com"));
    }

    @Test
    @DisplayName("compose fails fast on a null path variable value")
    void composeRejectsNullPathVariable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> compose("user", (Object) null));
        assertEquals("Path variable 'id' value cannot be null", error.getMessage());
    }

    @Test
    @DisplayName("compose sends an absolute verb path as-is, bypassing the base URL")
    void composeKeepsAbsolutePath() throws Exception {
        assertEquals("https://cdn.example.com/absolute",
                compose("absolute", URI.create("https://ignored.example.com/x")));
        assertEquals("http://cdn.example.com/plain",
                compose("httpAbsolute", URI.create("https://ignored.example.com/x")));
        assertEquals("http://plain.example.com/x", compose("plainAbsolute"));
    }

    @Test
    @DisplayName("compose uses @TargetUrl as the whole URL when the verb path is empty")
    void composeUsesTargetUrlAlone() throws Exception {
        assertEquals("https://s3.example.com/bucket/key?sig=abc", compose("at",
                URI.create("https://s3.example.com/bucket/key?sig=abc")));
    }

    @Test
    @DisplayName("compose fails fast on a null @TargetUrl value")
    void composeRejectsNullTargetUrl() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> compose("at", (Object) null));
        assertEquals("@TargetUrl parameter value cannot be null", error.getMessage());
    }

    @Test
    @DisplayName("compose merges the static path query before the @TargetUrl query")
    void composeMergesStaticPathQueryWithTargetQuery() throws Exception {
        assertEquals("https://s3.example.com/items?size=10&sig=abc",
                compose("itemsAt", URI.create("https://s3.example.com?sig=abc")));
        assertEquals("https://s3.example.com/items?size=10",
                compose("itemsAt", URI.create("https://s3.example.com")));
        assertEquals("https://s3.example.com/path/users?sig=abc",
                compose("usersAt", URI.create("https://s3.example.com/path?sig=abc")));
    }

    @Test
    @DisplayName("compose keeps both query and fragment when the @TargetUrl carries both")
    void composeKeepsTargetQueryAndFragment() throws Exception {
        assertEquals("https://s3.example.com/path/items?size=10&sig=abc#frag",
                compose("itemsAt", URI.create("https://s3.example.com/path?sig=abc#frag")));
    }

    @Test
    @DisplayName("compose inserts the static path query before a @TargetUrl fragment")
    void composeMergesStaticPathQueryWithTargetFragment() throws Exception {
        assertEquals("https://s3.example.com/items?size=10#frag",
                compose("itemsAt", URI.create("https://s3.example.com#frag")));
    }

    @Test
    @DisplayName("resolve appends params in one pass, keeps the fragment last and encodes names and values")
    void resolveAppendsEncodedParams() {
        URI uri = UrlComposer.resolve("http://host/api/users?active=true#section", List.of(
                new Param("role", "admin"),
                new Param("flag", null),
                new Param("name", "John Doe"),
                new Param("slug", "привет")));
        assertEquals("http://host/api/users?active=true&role=admin&flag&name=John%20Doe"
                        + "&slug=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82#section",
                uri.toString());
    }

    @Test
    @DisplayName("resolve handles every query/fragment position without losing the fragment")
    void resolveHandlesQueryAndFragmentPositions() {
        Param role = new Param("role", "admin");
        assertEquals("http://host/api?active=true&role=admin",
                UrlComposer.resolve("http://host/api?active=true", List.of(role)).toString());
        assertEquals("http://host/api?role=admin#frag",
                UrlComposer.resolve("http://host/api#frag", List.of(role)).toString());
        assertEquals("http://host/api?role=admin#frag?x=1",
                UrlComposer.resolve("http://host/api#frag?x=1", List.of(role)).toString());
    }

    @Test
    @DisplayName("resolve returns the plain URI when there are no request params")
    void resolveWithoutParams() {
        assertEquals("http://host/api/users", UrlComposer.resolve("http://host/api/users", List.of()).toString());
        assertEquals("http://host/api/users", UrlComposer.resolve("http://host/api/users", null).toString());
    }

    @Test
    @DisplayName("appendQuery inserts before the fragment and adds the right separator")
    void appendQueryPlacesQueryBeforeFragment() {
        assertEquals("http://host/api", UrlComposer.appendQuery("http://host/api", null).toString());
        assertEquals("http://host/api", UrlComposer.appendQuery("http://host/api", "").toString());
        assertEquals("http://host/api?page=2", UrlComposer.appendQuery("http://host/api", "page=2").toString());
        assertEquals("http://host/api?active=true&page=2",
                UrlComposer.appendQuery("http://host/api?active=true", "page=2").toString());
        assertEquals("http://host/api?page=2#frag",
                UrlComposer.appendQuery("http://host/api#frag", "page=2").toString());
        assertEquals("http://host/api?active=true&page=2#frag",
                UrlComposer.appendQuery("http://host/api?active=true#frag", "page=2").toString());
        assertEquals("http://host/api?page=2#frag?x=1",
                UrlComposer.appendQuery("http://host/api#frag?x=1", "page=2").toString());
    }
}
