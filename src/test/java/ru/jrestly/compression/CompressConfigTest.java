package ru.jrestly.compression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ControllerValidationException;
import ru.jrestly.ControllerValidator;
import ru.jrestly.JRestlyClient;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Request Compression Configuration & Validation Tests (ADR-0041)")
class CompressConfigTest {

    interface ValidApi {
        @Post(path = "/api/data")
        @Compress(algorithm = CompressionAlgorithm.GZIP, minSizeBytes = 256)
        String sendData(@RequestBody String data);
    }

    @Compress(algorithm = CompressionAlgorithm.DEFLATE, minSizeBytes = 512)
    interface ValidInterfaceApi {
        @Post(path = "/api/data")
        String sendData(@RequestBody String data);

        @Post(path = "/api/plain")
        @Compress(enabled = false)
        String sendPlain(@RequestBody String data);
    }

    interface InvalidMethodApi {
        @Post(path = "/api/data")
        @Compress(minSizeBytes = -1)
        String sendData(@RequestBody String data);
    }

    @Compress(minSizeBytes = -50)
    interface InvalidInterfaceApi {
        @Post(path = "/api/data")
        String sendData(@RequestBody String data);
    }

    @Test
    @DisplayName("ModuleInfo default methods return expected initial values")
    void testModuleInfoDefaults() {
        ModuleInfo info = new ModuleInfo() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> ru.jrestly.Environment<T> getEnvironment() {
                return null;
            }

            @Override
            public ru.jrestly.AuthProvider getAuthProvider() {
                return null;
            }

            @Override
            public ru.jrestly.json.JsonCodec getJsonCodec() {
                return null;
            }

            @Override
            public int getConnectTimeout() {
                return 0;
            }

            @Override
            public int getSocketTimeout() {
                return 0;
            }

            @Override
            public java.net.http.HttpClient.Version getHttpVersion() {
                return null;
            }

            @Override
            public boolean isValidateControllers() {
                return false;
            }

            @Override
            public ru.jrestly.http.RetryPolicy getRetryPolicy() {
                return null;
            }
        };

        assertNull(info.getRequestCompressionAlgorithm());
        assertEquals(0, info.getRequestCompressionThreshold());
        assertTrue(info.getContentCompressors().isEmpty());
        assertNull(info.getContentCompressor("gzip"));
        assertNull(info.getContentCompressor(null));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder configures compression algorithm and threshold")
    void testSimpleModuleInfoBuilder() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .requestCompression(CompressionAlgorithm.GZIP)
                .build();

        assertEquals(CompressionAlgorithm.GZIP, info.getRequestCompressionAlgorithm());
        assertEquals(0, info.getRequestCompressionThreshold());

        SimpleModuleInfo infoWithThreshold = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .requestCompression(CompressionAlgorithm.DEFLATE, 2048)
                .build();

        assertEquals(CompressionAlgorithm.DEFLATE, infoWithThreshold.getRequestCompressionAlgorithm());
        assertEquals(2048, infoWithThreshold.getRequestCompressionThreshold());

        assertThrows(IllegalArgumentException.class, () ->
                SimpleModuleInfo.builder().requestCompression(CompressionAlgorithm.GZIP, -10));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder registers and resolves custom ContentCompressor")
    void testCustomCompressorRegistration() {
        ContentCompressor dummy = new ContentCompressor() {
            @Override
            public String encoding() {
                return "dummy";
            }

            @Override
            public byte[] compress(byte[] uncompressed) {
                return uncompressed;
            }
        };

        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .contentCompressor(dummy)
                .build();

        assertEquals(1, info.getContentCompressors().size());
        assertSame(dummy, info.getContentCompressor("dummy"));
        assertSame(dummy, info.getContentCompressor(" DUMMY "));

        assertThrows(NullPointerException.class, () ->
                SimpleModuleInfo.builder().contentCompressor(null));

        assertThrows(IllegalArgumentException.class, () ->
                SimpleModuleInfo.builder().contentCompressor(new ContentCompressor() {
                    @Override
                    public String encoding() {
                        return "";
                    }

                    @Override
                    public byte[] compress(byte[] uncompressed) {
                        return uncompressed;
                    }
                }));
    }

    @Test
    @DisplayName("JRestlyClient.Builder exposes fluent compression configuration")
    void testJRestlyClientBuilderCompression() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .requestCompression(CompressionAlgorithm.GZIP, 1024)
                .build();

        assertEquals(CompressionAlgorithm.GZIP, client.getModuleInfo().getRequestCompressionAlgorithm());
        assertEquals(1024, client.getModuleInfo().getRequestCompressionThreshold());
        client.close();
    }

    @Test
    @DisplayName("ControllerValidator accepts valid @Compress declarations")
    void testControllerValidatorValid() {
        List<String> problems1 = ControllerValidator.validate(ValidApi.class);
        assertTrue(problems1.isEmpty(), "ValidApi should have 0 problems: " + problems1);

        List<String> problems2 = ControllerValidator.validate(ValidInterfaceApi.class);
        assertTrue(problems2.isEmpty(), "ValidInterfaceApi should have 0 problems: " + problems2);
    }

    @Test
    @DisplayName("ControllerValidator rejects negative minSizeBytes on method and interface")
    void testControllerValidatorNegativeThreshold() {
        List<String> problemsMethod = ControllerValidator.validate(InvalidMethodApi.class);
        assertFalse(problemsMethod.isEmpty());
        assertTrue(problemsMethod.getFirst().contains("@Compress minSizeBytes must not be negative"));

        List<String> problemsInterface = ControllerValidator.validate(InvalidInterfaceApi.class);
        assertFalse(problemsInterface.isEmpty());
        assertTrue(problemsInterface.getFirst().contains("@Compress minSizeBytes must not be negative"));
    }
}
