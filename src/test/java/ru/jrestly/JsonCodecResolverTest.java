package ru.jrestly;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.json.JacksonCodec;
import ru.jrestly.json.JsonCodec;
import ru.jrestly.json.JsonCodecResolver;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class JsonCodecResolverTest {

    @Test
    @DisplayName("resolve() probes the classpath and returns the Jackson defaults() codec")
    void resolveProbesJacksonFromClasspath() {
        JsonCodec codec = JsonCodecResolver.resolve();

        assertNotNull(codec);
        assertInstanceOf(JacksonCodec.class, codec,
                "Jackson is on the test classpath, the probe must find it");

        // Functional pin: the resolved codec is JacksonCodec.defaults() with the
        // jsr310 pattern, not a bare mapper wrapper
        assertEquals("\"2026-08-24 12:34:56\"",
                codec.serialize(LocalDateTime.of(2026, 8, 24, 12, 34, 56)));
    }

    @Test
    @DisplayName("explicit jsonCodec(...) wins over the classpath probe in SimpleModuleInfo.Builder")
    void explicitCodecWinsOverProbe() {
        JsonCodec explicit = new JacksonCodec(new ObjectMapper());

        ModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .jsonCodec(explicit)
                .build();

        assertSame(explicit, moduleInfo.getJsonCodec());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder without jsonCodec(...) falls back to the classpath probe")
    void builderFallsBackToProbe() {
        ModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .build();

        JsonCodec codec = moduleInfo.getJsonCodec();
        assertNotNull(codec, "Jackson is on the test classpath, the fallback probe must resolve a codec");
        assertInstanceOf(JacksonCodec.class, codec);
    }
}
