package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestAuthProvider;
import java.net.http.HttpClient;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage of executor and virtual-thread configuration (ADR-0026): the defaults on
 * {@code ModuleInfo}/{@code SimpleModuleInfo}, builder wiring, validation and lifecycle of
 * managed executors.
 */
@DisplayName("Executor and virtual threads configuration tests (ADR-0026)")
class ExecutorConfigTest {

    @Test
    @DisplayName("ModuleInfo default methods return null and false")
    void moduleInfoDefaults() {
        ModuleInfo defaultInfo = new ModuleInfo() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public <T> Environment<T> getEnvironment() {
                return null;
            }

            @Override
            public AuthProvider getAuthProvider() {
                return new TestAuthProvider();
            }
        };

        assertNull(defaultInfo.getExecutor(), "ModuleInfo.getExecutor() should default to null");
        assertFalse(defaultInfo.isVirtualThreads(), "ModuleInfo.isVirtualThreads() should default to false");
    }

    @Test
    @DisplayName("SimpleModuleInfo defaults have null executor and false virtualThreads")
    void simpleModuleInfoDefaults() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        assertNull(info.getExecutor());
        assertFalse(info.isVirtualThreads());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder configures custom executor and rejects null")
    void simpleModuleInfoCustomExecutor() {
        Executor customExecutor = Runnable::run;

        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .executor(customExecutor)
                .build();

        assertSame(customExecutor, info.getExecutor());
        assertFalse(info.isVirtualThreads());

        NullPointerException npe = assertThrows(NullPointerException.class, () ->
                SimpleModuleInfo.builder().baseUrl("http://localhost:8080").executor(null));
        assertEquals("executor", npe.getMessage());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder configures virtualThreads flag")
    void simpleModuleInfoVirtualThreads() {
        SimpleModuleInfo info1 = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .virtualThreads(true)
                .build();

        assertTrue(info1.isVirtualThreads());
        assertNull(info1.getExecutor());

        SimpleModuleInfo info2 = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .useVirtualThreads()
                .build();

        assertTrue(info2.isVirtualThreads());

        SimpleModuleInfo info3 = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .virtualThreads(false)
                .build();

        assertFalse(info3.isVirtualThreads());
    }

    @Test
    @DisplayName("JRestlyClient.Builder forwards executor and virtualThreads settings")
    void jrestlyClientBuilderForwarding() {
        Executor customExecutor = Runnable::run;

        JRestlyClient clientWithExecutor = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .executor(customExecutor)
                .build();

        try {
            assertSame(customExecutor, clientWithExecutor.getModuleInfo().getExecutor());
            assertSame(customExecutor, clientWithExecutor.getExecutor());
            assertFalse(clientWithExecutor.getModuleInfo().isVirtualThreads());
        } finally {
            clientWithExecutor.close();
        }

        JRestlyClient clientWithVirtual = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .useVirtualThreads()
                .build();

        try {
            assertTrue(clientWithVirtual.getModuleInfo().isVirtualThreads());
            assertNotNull(clientWithVirtual.getExecutor(), "AbstractHttpClient should create managed executor for virtual threads");
        } finally {
            clientWithVirtual.close();
        }

        NullPointerException npe = assertThrows(NullPointerException.class, () ->
                JRestlyClient.builder().baseUrl("http://localhost:8080").executor(null));
        assertEquals("executor", npe.getMessage());
    }

    @Test
    @DisplayName("AbstractHttpClient closes internally managed virtual thread executor on close()")
    void abstractHttpClientClosesManagedVirtualThreadExecutor() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .useVirtualThreads()
                .build();

        Executor executor = client.getExecutor();
        assertNotNull(executor);
        assertTrue(executor instanceof ExecutorService);
        ExecutorService executorService = (ExecutorService) executor;
        assertFalse(executorService.isShutdown());

        client.close();
        assertTrue(executorService.isShutdown(), "Managed executor should be shut down when client closes");

        // Safe to call close twice
        client.close();
        assertTrue(executorService.isShutdown());
    }

    @Test
    @DisplayName("AbstractHttpClient does not close external executor on close()")
    void abstractHttpClientDoesNotCloseExternalExecutor() {
        ExecutorService externalExecutor = Executors.newSingleThreadExecutor();

        try {
            JRestlyClient client = JRestlyClient.builder()
                    .baseUrl("http://localhost:8080")
                    .executor(externalExecutor)
                    .build();

            assertSame(externalExecutor, client.getExecutor());

            client.close();
            assertFalse(externalExecutor.isShutdown(), "External executor must not be closed by AbstractHttpClient.close()");
        } finally {
            externalExecutor.shutdown();
        }
    }
}
