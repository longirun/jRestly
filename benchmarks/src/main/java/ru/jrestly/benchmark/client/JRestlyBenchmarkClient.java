package ru.jrestly.benchmark.client;

import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.feed.FeedEntry;
import ru.jrestly.benchmark.feed.FeedParser;

import java.io.InputStream;
import java.util.List;

import javax.xml.stream.XMLStreamException;

/**
 * jRestly declarative client adapter for comparative benchmarks.
 */
public final class JRestlyBenchmarkClient implements AutoCloseable {

    /**
     * Declarative API interface definition for jRestly.
     */
    public interface UserApi {
        @Get("/api/users/{id}")
        UserDto getUser(@PathVariable long id);

        @Get("/api/ping")
        String ping();

        @Get("/api/users/batch")
        java.util.List<UserDto> getUsersBatch();

        @Post("/api/users")
        UserDto createUser(@RequestBody UserDto user);
    }

    /**
     * Declarative feed API for the real-scenario fetch-and-parse benchmark (ADR-0068 &sect;4).
     * The two arms are the main buffered vs streaming cut of the benchmark: the same
     * route either arrives fully assembled as {@code byte[]} (the parser then reads it
     * from a zero-copy {@code ByteArrayInputStream}) or as the live wire stream
     * ({@code BodyHandlers.ofInputStream()} semantics in the core, no intermediate
     * full copy) fed straight into the shared parser.
     */
    public interface FeedApi {
        @Get("/feed/{kind}")
        byte[] feedBuffered(@PathVariable String kind);

        @Get("/feed/{kind}")
        InputStream feedStreaming(@PathVariable String kind);
    }

    private final JRestlyClient client;
    private final UserApi api;
    private final FeedApi feedApi;
    private final FeedParser feedParser = new FeedParser();

    /**
     * Creates a client pinned to the zero-dependency JDK transport: with the engine
     * submodules on the classpath a default client would auto-detect a third-party
     * engine via {@code ServiceLoader} (ADR-0058), silently hijacking the
     * "jrestly (JDK)" benchmark row.
     *
     * @param baseUrl the benchmark server base URL
     */
    public JRestlyBenchmarkClient(String baseUrl) {
        this(baseUrl, ru.jrestly.engine.JdkHttpEngine.create(
                ru.jrestly.SimpleModuleInfo.builder().baseUrl(baseUrl).build()));
    }

    public JRestlyBenchmarkClient(String baseUrl, ru.jrestly.engine.HttpEngine engine) {
        var builder = JRestlyClient.builder()
                .baseUrl(baseUrl)
                .logger(ru.jrestly.benchmark.util.NoOpLogger.INSTANCE)
                // Pinned: the codec-* submodules are on the benchmark classpath for the
                // polyglot matrix, and ServiceLoader would otherwise widen the registry
                // behind this JSON baseline (same rationale as the engine pin above).
                .codecs(ru.jrestly.codec.CodecRegistry.of(ru.jrestly.json.JsonCodecResolver.resolve()));
        if (engine != null) {
            builder.httpEngine(engine);
        }
        this.client = builder.build();
        this.api = client.get(UserApi.class);
        this.feedApi = client.get(FeedApi.class);
    }

    public JRestlyBenchmarkClient(String baseUrl, java.net.http.HttpClient.Version version) {
        this.client = JRestlyClient.builder()
                .baseUrl(baseUrl)
                .httpVersion(version)
                .logger(ru.jrestly.benchmark.util.NoOpLogger.INSTANCE)
                .codecs(ru.jrestly.codec.CodecRegistry.of(ru.jrestly.json.JsonCodecResolver.resolve()))
                .build();
        this.api = client.get(UserApi.class);
        this.feedApi = client.get(FeedApi.class);
    }

    public UserApi api() {
        return api;
    }

    public UserDto getUser(long id) {
        return api.getUser(id);
    }

    public String ping() {
        return api.ping();
    }

    public java.util.List<UserDto> getUsersBatch() {
        return api.getUsersBatch();
    }

    public UserDto createUser(UserDto user) {
        return api.createUser(user);
    }

    /**
     * Buffered arm: the full document arrives as {@code byte[]}, then the shared
     * parser reads it without an extra copy of the body bytes.
     *
     * @param kind feed route suffix, one of {@code rss-20|200|1000} or {@code atom-*}
     * @return parsed entries in document order
     * @throws XMLStreamException when the fetched document cannot be parsed
     */
    public List<FeedEntry> feedBuffered(String kind) throws XMLStreamException {
        return feedParser.parse(feedApi.feedBuffered(kind));
    }

    /**
     * Streaming arm: the shared parser reads the live wire stream directly, without an
     * intermediate full copy of the body.
     *
     * @param kind feed route suffix, one of {@code rss-20|200|1000} or {@code atom-*}
     * @return parsed entries in document order
     * @throws XMLStreamException when the fetched document cannot be parsed
     */
    public List<FeedEntry> feedStreaming(String kind) throws XMLStreamException {
        return feedParser.parse(feedApi.feedStreaming(kind));
    }

    @Override
    public void close() {
        client.close();
    }
}
