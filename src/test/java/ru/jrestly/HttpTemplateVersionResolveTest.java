package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.HttpVersionController;
import ru.jrestly.fixtures.TestModuleInfo;
import ru.jrestly.http.HttpTemplate;
import ru.jrestly.http.HttpTemplateBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Per-method @HttpVersion resolution: HttpTemplateBuilder.build() must put the
 * annotated version into the template, and leave null (inherit the client-level
 * version) for unannotated methods. No HTTP traffic — templates are inspected
 * right after build().
 */
class HttpTemplateVersionResolveTest {

    @Test
    @DisplayName("@HttpVersion(HTTP_1_1) on a controller method is resolved into the HttpTemplate")
    void annotatedMethodResolvesHttp11() throws Exception {
        HttpTemplate template = buildTemplate("getPinnedToHttp11");

        assertEquals(HttpClient.Version.HTTP_1_1, readVersion(template));
    }

    @Test
    @DisplayName("a method without @HttpVersion resolves to null (inherit the client-level version)")
    void unannotatedMethodResolvesNull() throws Exception {
        HttpTemplate template = buildTemplate("getUnpinned");

        assertNull(readVersion(template));
    }

    private static HttpTemplate buildTemplate(String methodName) throws NoSuchMethodException {
        Method method = HttpVersionController.class.getMethod(methodName);
        TestModuleInfo moduleInfo = new TestModuleInfo("http://localhost:1");

        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            return new HttpTemplateBuilder(moduleInfo, httpClient, method, new Object[0], HttpVersionController.class)
                    .build();
        }
    }

    private static HttpClient.Version readVersion(HttpTemplate template) throws ReflectiveOperationException {
        // HttpTemplate exposes only a setter; read the private field for verification
        Field field = HttpTemplate.class.getDeclaredField("httpVersion");
        field.setAccessible(true);
        return (HttpClient.Version) field.get(template);
    }
}
