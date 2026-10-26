package ru.jrestly;

import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.RequestDefaultParam;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.fixtures.BrokenController;
import ru.jrestly.fixtures.TestController;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
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

    @RequestHeader(name = "", value = "v")
    @RequestDefaultParam(name = " ", value = "1")
    interface BlankStaticNamesApi {
        @Get(path = "/x")
        @RequestHeader(name = "", value = "v")
        String call();
    }

    @Test
    void rejectsBlankStaticNamesEagerly() {
        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();

        ControllerValidationException e = assertThrows(ControllerValidationException.class,
                () -> client.get(BlankStaticNamesApi.class));

        assertEquals(3, e.getProblems().size());
        assertTrue(e.getProblems().stream().anyMatch(p ->
                p.startsWith("BlankStaticNamesApi: @RequestHeader name must not be blank")));
        assertTrue(e.getProblems().stream().anyMatch(p ->
                p.startsWith("BlankStaticNamesApi: @RequestDefaultParam name must not be blank")));
        assertTrue(e.getProblems().stream().anyMatch(p ->
                p.startsWith("call(): @RequestHeader name must not be blank")));
    }

    @Test
    void rejectsParameterNameFallbackWithoutParametersFlagEagerly() throws Exception {
        // A blank name() falls back to the Java parameter name, which only exists
        // under the '-parameters' compiler flag. The interface is compiled at runtime
        // both ways to prove the guard keys on the flag, not on the declarations.
        String bindings = """
                @Get(path = "/search")
                String search(@RequestParam String query);

                @Get(path = "/api")
                String fetch(@RequestHeader String token);

                @Get(path = "/items/{id}")
                String item(@PathVariable String id);
                """;

        Class<?> withoutFlag = compileController("NoParamsController", bindings, false);
        List<String> problems = ControllerValidator.validate(withoutFlag);
        assertEquals(3, problems.size());
        assertTrue(problems.stream().anyMatch(p ->
                p.startsWith("search(): @RequestParam requires an explicit name()")));
        assertTrue(problems.stream().anyMatch(p ->
                p.startsWith("fetch(): @RequestHeader requires an explicit name()")));
        assertTrue(problems.stream().anyMatch(p ->
                p.startsWith("item(): @PathVariable requires an explicit name()")));

        JRestlyClient client = JRestlyClient.builder().baseUrl("http://localhost:1").build();
        assertThrows(ControllerValidationException.class, () -> client.get(withoutFlag));

        // the very same declarations are valid once the flag retains parameter names
        assertTrue(ControllerValidator.validate(compileController("ParamsController", bindings, true)).isEmpty());

        // a Map parameter takes its names from the entries, not the parameter name
        assertTrue(ControllerValidator.validate(compileController("MapController", """
                @Get(path = "/search")
                String search(@RequestParam java.util.Map<String, String> filters);
                """, false)).isEmpty());
    }

    // The test classpath is built with -parameters, so a class lacking the
    // MethodParameters attribute must be produced at runtime. The loader stays
    // open so the proxy can be built on the returned class.
    private static Class<?> compileController(String simpleName, String body, boolean parameters) throws Exception {
        Path dir = Files.createTempDirectory("jrestly-ctrl-" + simpleName);
        Path source = dir.resolve(simpleName + ".java");
        Files.writeString(source, """
                package ru.jrestly.gen;

                import ru.jrestly.annotation.Get;
                import ru.jrestly.annotation.PathVariable;
                import ru.jrestly.annotation.RequestHeader;
                import ru.jrestly.annotation.RequestParam;

                public interface %s {
                %s
                }
                """.formatted(simpleName, body));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the JDK compiler is required for this test");
        // java.class.path is the Gradle test-worker jar, not the runtime classpath —
        // the compiled classes location is the reliable compile target
        String classesLocation = PathVariable.class.getProtectionDomain().getCodeSource()
                .getLocation().getPath();
        List<String> options = new ArrayList<>(List.of(
                "-classpath", classesLocation,
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
