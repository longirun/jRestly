package ru.jrestly.benchmark.feed;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.benchmark.client.FeignBenchmarkClient;
import ru.jrestly.benchmark.client.JRestlyBenchmarkClient;
import ru.jrestly.benchmark.client.OkHttpBenchmarkClient;
import ru.jrestly.benchmark.client.RawHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.RetrofitBenchmarkClient;
import ru.jrestly.benchmark.client.SpringRestClientBenchmarkClient;
import ru.jrestly.benchmark.server.BenchmarkHttpServer;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end sanitary gate of the real-scenario fetch-and-parse matrix (ADR-0068 &sect;4):
 * every benchmark arm must fetch one of the six deterministic feed routes through its
 * own HTTP idiom and reach the expected entry count, and the two jRestly arms (buffered
 * {@code byte[]} vs streaming {@code InputStream}) must fold byte-identical summaries
 * over the same shared {@link FeedParser} &mdash; the parser is intentionally one for all
 * arms, so any divergence here can only be a fetch-side defect.
 */
@DisplayName("Feed fetch-and-parse matrix clients (ADR-0068 phase 2)")
class FeedBenchmarkClientsTest {

    private static final List<String> KINDS =
            List.of("rss-20", "rss-200", "rss-1000", "atom-20", "atom-200", "atom-1000");

    private static final Map<String, Integer> EXPECTED_COUNTS = Map.of(
            "rss-20", 20, "rss-200", 200, "rss-1000", 1000,
            "atom-20", 20, "atom-200", 200, "atom-1000", 1000);

    private static BenchmarkHttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = BenchmarkHttpServer.start();
        baseUrl = server.baseUrl();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("jRestly buffered and streaming arms fold identical summaries over all six routes")
    void jRestlyBufferedAndStreamingArmsFoldIdenticalSummaries() throws Exception {
        try (JRestlyBenchmarkClient jrestly = new JRestlyBenchmarkClient(baseUrl)) {
            for (String kind : KINDS) {
                FeedSummary buffered = FeedSummary.of(jrestly.feedBuffered(kind));
                FeedSummary streaming = FeedSummary.of(jrestly.feedStreaming(kind));

                assertEquals(EXPECTED_COUNTS.get(kind), buffered.count(), "buffered count: " + kind);
                assertEquals(EXPECTED_COUNTS.get(kind), streaming.count(), "streaming count: " + kind);
                assertEquals(buffered, streaming,
                        "buffered and streaming must fold into the same summary: " + kind);
            }
        }
    }

    @Test
    @DisplayName("jRestly Netty buffered and streaming arms fold identical summaries over all six routes")
    void jRestlyNettyBufferedAndStreamingArmsFoldIdenticalSummaries() throws Exception {
        try (JRestlyBenchmarkClient jrestly = new JRestlyBenchmarkClient(baseUrl, new ru.jrestly.engine.netty.NettyHttpEngine())) {
            for (String kind : KINDS) {
                FeedSummary buffered = FeedSummary.of(jrestly.feedBuffered(kind));
                FeedSummary streaming = FeedSummary.of(jrestly.feedStreaming(kind));

                assertEquals(EXPECTED_COUNTS.get(kind), buffered.count(), "Netty buffered count: " + kind);
                assertEquals(EXPECTED_COUNTS.get(kind), streaming.count(), "Netty streaming count: " + kind);
                assertEquals(buffered, streaming,
                        "Netty buffered and streaming must fold into the same summary: " + kind);
            }
        }
    }

    @Test
    @DisplayName("Every competitor arm fetches and reaches the expected entry count on all six routes")
    void competitorArmsReachExpectedCounts() throws Exception {
        try (OkHttpBenchmarkClient okHttp = new OkHttpBenchmarkClient(baseUrl);
             RetrofitBenchmarkClient retrofit = new RetrofitBenchmarkClient(baseUrl);
             FeignBenchmarkClient feign = new FeignBenchmarkClient(baseUrl);
             SpringRestClientBenchmarkClient spring = new SpringRestClientBenchmarkClient(baseUrl);
             RawHttpClientBenchmarkClient rawJdk = new RawHttpClientBenchmarkClient(baseUrl)) {
            for (String kind : KINDS) {
                assertEquals(EXPECTED_COUNTS.get(kind), okHttp.feed(kind).size(), "raw OkHttp: " + kind);
                assertEquals(EXPECTED_COUNTS.get(kind), retrofit.feed(kind).size(), "Retrofit: " + kind);
                assertEquals(EXPECTED_COUNTS.get(kind), feign.feed(kind).size(), "Feign: " + kind);
                assertEquals(EXPECTED_COUNTS.get(kind), spring.feed(kind).size(), "Spring: " + kind);
                assertEquals(EXPECTED_COUNTS.get(kind), rawJdk.feed(kind).size(), "raw JDK: " + kind);
            }
        }
    }
}
