package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ModuleInfo;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.fixtures.TestModuleInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ADR-0038: Verification of @HttpVersion annotation inheritance across
 * controller interface hierarchies (precedence: Method > Sub-interface > Super-interface > Client default).
 */
class HttpVersionHierarchyTest {

    interface UnannotatedBaseApi {
        @Get("/base")
        String baseCall();
    }

    @HttpVersion(HttpClient.Version.HTTP_1_1)
    interface BaseHttp11Api {
        @Get("/base11")
        String base11();

        @Get("/override2")
        @HttpVersion(HttpClient.Version.HTTP_2)
        String overrideToHttp2();
    }

    interface InheritingSubApi extends BaseHttp11Api {
        @Get("/sub-inherited")
        String subInherited();
    }

    @HttpVersion(HttpClient.Version.HTTP_2)
    interface OverridingSubApi extends BaseHttp11Api {
        @Get("/sub-override")
        String subOverride();

        @Get("/sub-method-11")
        @HttpVersion(HttpClient.Version.HTTP_1_1)
        String subMethod11();
    }

    @Test
    @DisplayName("Unannotated method on unannotated interface resolves to null (inherit client default)")
    void unannotatedResolvesNull() throws Exception {
        HttpTemplate template = buildTemplate(UnannotatedBaseApi.class, "baseCall");
        assertNull(readVersion(template));
    }

    @Test
    @DisplayName("Method inherits @HttpVersion(HTTP_1_1) from its direct declaring interface")
    void inheritsFromDirectInterface() throws Exception {
        HttpTemplate template = buildTemplate(BaseHttp11Api.class, "base11");
        assertEquals(HttpClient.Version.HTTP_1_1, readVersion(template));
    }

    @Test
    @DisplayName("Method-level @HttpVersion(HTTP_2) overrides declaring interface @HttpVersion(HTTP_1_1)")
    void methodOverridesDirectInterface() throws Exception {
        HttpTemplate template = buildTemplate(BaseHttp11Api.class, "overrideToHttp2");
        assertEquals(HttpClient.Version.HTTP_2, readVersion(template));
    }

    @Test
    @DisplayName("Sub-interface method inherits @HttpVersion(HTTP_1_1) from super-interface")
    void inheritsFromSuperInterface() throws Exception {
        HttpTemplate template = buildTemplate(InheritingSubApi.class, "subInherited");
        assertEquals(HttpClient.Version.HTTP_1_1, readVersion(template));
    }

    @Test
    @DisplayName("Sub-interface @HttpVersion(HTTP_2) overrides super-interface @HttpVersion(HTTP_1_1)")
    void subInterfaceOverridesSuperInterface() throws Exception {
        HttpTemplate template = buildTemplate(OverridingSubApi.class, "subOverride");
        assertEquals(HttpClient.Version.HTTP_2, readVersion(template));
    }

    @Test
    @DisplayName("Method-level @HttpVersion(HTTP_1_1) overrides sub-interface @HttpVersion(HTTP_2)")
    void methodOverridesSubInterface() throws Exception {
        HttpTemplate template = buildTemplate(OverridingSubApi.class, "subMethod11");
        assertEquals(HttpClient.Version.HTTP_1_1, readVersion(template));
    }

    private static HttpTemplate buildTemplate(Class<?> controllerClass, String methodName) throws NoSuchMethodException {
        Method method = controllerClass.getMethod(methodName);
        TestModuleInfo moduleInfo = new TestModuleInfo("http://localhost:1");

        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            return new HttpTemplateBuilder(moduleInfo, httpClient, method, new Object[0], controllerClass)
                    .build();
        }
    }

    private static HttpClient.Version readVersion(HttpTemplate template) throws ReflectiveOperationException {
        Field field = HttpTemplate.class.getDeclaredField("httpVersion");
        field.setAccessible(true);
        return (HttpClient.Version) field.get(template);
    }
}
