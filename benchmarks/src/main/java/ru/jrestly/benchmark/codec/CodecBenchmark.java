package ru.jrestly.benchmark.codec;

import com.google.protobuf.Timestamp;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.codec.CodecRegistry;
import ru.jrestly.codec.JsonCodecAdapter;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.codec.jacksonxml.JacksonXmlCodec;
import ru.jrestly.codec.protobuf.ProtobufCodec;
import ru.jrestly.codec.yaml.YamlCodec;
import ru.jrestly.http.MediaType;
import ru.jrestly.json.JacksonCodec;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * In-memory overhead of the {@link PayloadCodec} SPI per format (cycle 0.13.0):
 * one representative payload per codec, encode and decode arms, plus the
 * {@link CodecRegistry} resolution that runs once per response on the hot path.
 *
 * <p>This is a codec-overhead benchmark, not a format ranking: the payloads are
 * deliberately different because the formats are (a JSON/XML/YAML
 * {@link UserDto} document vs a schema-bound protobuf {@link Timestamp}). The
 * numbers answer "what does the SPI cost per call", not "which format is
 * smaller or faster for the same data".
 *
 * <p>Media-type-aware codecs are registered explicitly: {@code codec-*} modules
 * are on the benchmark classpath, and letting {@code ServiceLoader} discover
 * them would make the JSON row depend on unrelated jars (the same classpath
 * pinning rationale as the transport engines, ADR-0058).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class CodecBenchmark {

    private static final String JSON_MEDIA_TYPE = MediaType.APPLICATION_JSON;
    private static final String XML_MEDIA_TYPE = MediaType.APPLICATION_XML;
    private static final String YAML_MEDIA_TYPE = MediaType.APPLICATION_YAML;
    private static final String PROTOBUF_MEDIA_TYPE = MediaType.APPLICATION_X_PROTOBUF;

    private static final UserDto USER = new UserDto(
            42L,
            "Alice",
            "alice@example.com",
            true,
            List.of("admin", "engineer"),
            "2026-10-20T12:00:00Z");

    private static final Timestamp TIMESTAMP = Timestamp.newBuilder()
            .setSeconds(1_792_500_000L)
            .setNanos(123_456_789)
            .build();

    private final PayloadCodec json = new JsonCodecAdapter(JacksonCodec.defaults());
    private final PayloadCodec xml = JacksonXmlCodec.defaults();
    private final PayloadCodec yaml = YamlCodec.defaults();
    private final PayloadCodec protobuf = ProtobufCodec.defaults();

    /**
     * A response-path registry in the shape a client would build for a
     * polyglot API: JSON default, XML/YAML/protobuf as alternatives.
     */
    private final CodecRegistry registry = CodecRegistry.builder()
            .defaultCodec(json)
            .codec(xml)
            .codec(yaml)
            .codec(protobuf)
            .build();

    private final byte[] jsonBytes = json.serialize(USER, JSON_MEDIA_TYPE);
    private final byte[] xmlBytes = xml.serialize(USER, XML_MEDIA_TYPE);
    private final byte[] yamlBytes = yaml.serialize(USER, YAML_MEDIA_TYPE);
    private final byte[] protobufBytes = protobuf.serialize(TIMESTAMP, PROTOBUF_MEDIA_TYPE);

    // --- JSON (zero-dependency default path) ---

    @Benchmark
    public UserDto json_decode() {
        return json.deserialize(jsonBytes, UserDto.class, JSON_MEDIA_TYPE);
    }

    @Benchmark
    public byte[] json_encode() {
        return json.serialize(USER, JSON_MEDIA_TYPE);
    }

    // --- XML (codec-jackson-xml) ---

    @Benchmark
    public UserDto xml_decode() {
        return xml.deserialize(xmlBytes, UserDto.class, XML_MEDIA_TYPE);
    }

    @Benchmark
    public byte[] xml_encode() {
        return xml.serialize(USER, XML_MEDIA_TYPE);
    }

    // --- YAML (codec-yaml) ---

    @Benchmark
    public UserDto yaml_decode() {
        return yaml.deserialize(yamlBytes, UserDto.class, YAML_MEDIA_TYPE);
    }

    @Benchmark
    public byte[] yaml_encode() {
        return yaml.serialize(USER, YAML_MEDIA_TYPE);
    }

    // --- Protocol Buffers (codec-protobuf, schema-bound) ---

    @Benchmark
    public Timestamp protobuf_decode() {
        return protobuf.deserialize(protobufBytes, Timestamp.class, PROTOBUF_MEDIA_TYPE);
    }

    @Benchmark
    public byte[] protobuf_encode() {
        return protobuf.serialize(TIMESTAMP, PROTOBUF_MEDIA_TYPE);
    }

    // --- Registry resolution (once per request/response on the hot path) ---

    @Benchmark
    public PayloadCodec registry_resolveJson() {
        return registry.resolveOrDefault("Application/JSON; charset=UTF-8");
    }

    /**
     * Wire size of each representative payload, for the published table.
     *
     * @return sizes in bytes, in the order JSON, XML, YAML, protobuf
     */
    public int[] wireSizes() {
        return new int[]{jsonBytes.length, xmlBytes.length, yamlBytes.length, protobufBytes.length};
    }
}
