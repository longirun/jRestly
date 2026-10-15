package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.fixtures.TestController;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExecutorResolutionTest {

    @Test
    @DisplayName("HttpTemplateBuilder sets custom executor on HttpTemplate")
    void httpTemplateBuilderSetsExecutor() throws NoSuchMethodException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        Method method = TestController.class.getMethod("getItem", Long.class);

        HttpTemplateBuilder builder = new HttpTemplateBuilder(moduleInfo, httpClient, method, new Object[]{1L}, TestController.class);
        HttpTemplate templateWithoutExecutor = builder.build();
        assertNull(templateWithoutExecutor.getExecutor());

        Executor customExecutor = Runnable::run;
        builder.executor(customExecutor);
        HttpTemplate templateWithExecutor = builder.build();
        assertSame(customExecutor, templateWithExecutor.getExecutor());
    }
}
