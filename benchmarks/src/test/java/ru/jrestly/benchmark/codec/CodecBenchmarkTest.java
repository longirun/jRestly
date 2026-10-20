package ru.jrestly.benchmark.codec;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.codec.PayloadCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity gate for {@link CodecBenchmark}: every arm executes and binds its own
 * payload back to the expected value, so the published numbers cannot silently
 * measure a failing codec path.
 */
@DisplayName("CodecBenchmark arm sanity")
class CodecBenchmarkTest {

    @Test
    @DisplayName("All eight encode/decode arms and the registry arm execute correctly")
    void everyArmBindsItsPayload() {
        CodecBenchmark benchmark = new CodecBenchmark();

        assertEquals("Alice", benchmark.json_decode().name());
        assertEquals("Alice", benchmark.xml_decode().name());
        assertEquals("Alice", benchmark.yaml_decode().name());

        assertEquals(1_792_500_000L, benchmark.protobuf_decode().getSeconds());
        assertEquals(123_456_789, benchmark.protobuf_decode().getNanos());

        UserDto encodedJson = benchmark.json_decode();
        assertEquals(42L, encodedJson.id());
        assertEquals("2026-10-20T12:00:00Z", encodedJson.createdAt());

        assertTrue(benchmark.json_encode().length > 0);
        assertTrue(benchmark.xml_encode().length > 0);
        assertTrue(benchmark.yaml_encode().length > 0);
        assertTrue(benchmark.protobuf_encode().length > 0);

        PayloadCodec resolved = benchmark.registry_resolveJson();
        assertNotNull(resolved);
        assertTrue(resolved.supports("application/json"));
    }

    @Test
    @DisplayName("Representative wire sizes are ordered protobuf < YAML < JSON < XML")
    void wireSizesDescribeTheRepresentativePayloads() {
        int[] sizes = new CodecBenchmark().wireSizes();
        assertEquals(4, sizes.length);
        for (int size : sizes) {
            assertTrue(size > 0, "every representative payload must be non-empty");
        }
        assertTrue(sizes[3] < sizes[2], "protobuf Timestamp is the smallest representative payload");
        assertTrue(sizes[2] < sizes[0], "YAML is more compact than the JSON document");
        assertTrue(sizes[0] < sizes[1], "Jackson XML wrappers make XML the largest");
    }
}
