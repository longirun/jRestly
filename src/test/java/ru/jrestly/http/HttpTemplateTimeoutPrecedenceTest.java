package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Timeout;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HttpTemplateTimeoutPrecedenceTest {

    interface SuperApi {
        @Get(path = "/super")
        String superMethod();
    }

    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    interface InterfaceTimeoutApi {
        @Get(path = "/inherited")
        String inherited();

        @Timeout(500)
        @Get(path = "/override")
        String overridden();
    }

    @Timeout(2000)
    interface ParentApi {
        @Get(path = "/parent")
        String parentMethod();
    }

    @Timeout(500)
    interface ChildApi extends ParentApi {
        @Get(path = "/child")
        String childMethod();
    }

    @Test
    @DisplayName("HttpTemplateBuilder resolves timeouts with correct precedence")
    void timeoutPrecedenceResolution() throws NoSuchMethodException {
        ModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost")
                .socketTimeout(25_000)
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        // 1. No annotation -> null template timeout (falls back to moduleInfo in toHttpRequest)
        Method superMethod = SuperApi.class.getMethod("superMethod");
        HttpTemplate template1 = new HttpTemplateBuilder(moduleInfo, httpClient, superMethod, new Object[0], SuperApi.class).build();
        assertNull(template1.getTimeout());

        // 2. Interface level timeout
        Method inheritedMethod = InterfaceTimeoutApi.class.getMethod("inherited");
        HttpTemplate template2 = new HttpTemplateBuilder(moduleInfo, httpClient, inheritedMethod, new Object[0], InterfaceTimeoutApi.class).build();
        assertEquals(Duration.ofSeconds(10), template2.getTimeout());

        // 3. Method level overrides interface level
        Method overriddenMethod = InterfaceTimeoutApi.class.getMethod("overridden");
        HttpTemplate template3 = new HttpTemplateBuilder(moduleInfo, httpClient, overriddenMethod, new Object[0], InterfaceTimeoutApi.class).build();
        assertEquals(Duration.ofMillis(500), template3.getTimeout());

        // 4. Sub-interface overrides super-interface
        Method childMethod = ChildApi.class.getMethod("childMethod");
        HttpTemplate template4 = new HttpTemplateBuilder(moduleInfo, httpClient, childMethod, new Object[0], ChildApi.class).build();
        assertEquals(Duration.ofMillis(500), template4.getTimeout());
    }
}
