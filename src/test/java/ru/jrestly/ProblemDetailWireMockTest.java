package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.http.ClientErrorException;
import ru.jrestly.http.HandledException;
import ru.jrestly.http.ServerErrorException;
import ru.jrestly.problem.ProblemDetail;
import ru.jrestly.problem.ProblemDetailException;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end WireMock test suite for RFC 7807 and RFC 9457 Problem Details error ergonomics (ADR-0064).
 */
public class ProblemDetailWireMockTest extends BaseWireMockTest {

    interface ProblemApiClient {
        @Get("/accounts/${id}")
        String getAccount(@PathVariable(name = "id") String id);

        @Post("/transfer")
        String transfer();

        @Get("/error-handled-pd")
        @OnError(statuses = 404, errorObject = ProblemDetail.class)
        String getWithProblemDetail();

        @Get("/error-handled-ex")
        @OnError(statuses = 422, errorObject = ProblemDetailException.class)
        String getWithProblemDetailException();

        @Get("/error-handled-bom")
        @OnError(statuses = 422, errorObject = ProblemDetailException.class)
        String getWithProblemDetailBom();

        @Get("/async-error")
        CompletableFuture<String> getAsync();
    }

    private ProblemApiClient createClient() {
        return client.get(ProblemApiClient.class);
    }

    @Test
    @DisplayName("Unexpected 403 ClientErrorException automatically resolves ProblemDetail and extension properties")
    void clientErrorResolvesProblemDetail() {
        String problemJson = """
                {
                  "type": "https://example.com/probs/out-of-credit",
                  "title": "You do not have enough credit.",
                  "status": 403,
                  "detail": "Your current balance is 30, but that costs 50.",
                  "instance": "/account/12345/msgs/abc",
                  "balance": 30,
                  "errorCode": "INSUFFICIENT_FUNDS"
                }
                """;

        stubFor(get(urlEqualTo("/accounts/12345"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(problemJson)));

        ProblemApiClient client = createClient();
        ClientErrorException ex = assertThrows(ClientErrorException.class, () -> client.getAccount("12345"));

        assertEquals(403, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("403"));
        assertTrue(ex.getMessage().contains("You do not have enough credit."));
        assertTrue(ex.getMessage().contains("Your current balance is 30, but that costs 50."));

        Optional<ProblemDetail> maybePd = ex.getProblemDetail();
        assertTrue(maybePd.isPresent());
        ProblemDetail pd = maybePd.get();
        assertEquals(URI.create("https://example.com/probs/out-of-credit"), pd.getType());
        assertEquals("You do not have enough credit.", pd.getTitle());
        assertEquals(403, pd.getStatus());
        assertEquals("Your current balance is 30, but that costs 50.", pd.getDetail());
        assertEquals(URI.create("/account/12345/msgs/abc"), pd.getInstance());
        assertEquals(30, pd.getProperty("balance"));
        assertEquals("INSUFFICIENT_FUNDS", pd.getProperty("errorCode"));
    }

    @Test
    @DisplayName("Unexpected 500 ServerErrorException automatically resolves ProblemDetail")
    void serverErrorResolvesProblemDetail() {
        String problemJson = """
                {
                  "type": "https://example.com/probs/database-failure",
                  "title": "Internal Server Error",
                  "status": 500,
                  "detail": "Connection pool exhausted",
                  "retryAfter": 5
                }
                """;

        stubFor(post(urlEqualTo("/transfer"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(problemJson)));

        ProblemApiClient client = createClient();
        ServerErrorException ex = assertThrows(ServerErrorException.class, client::transfer);

        assertEquals(500, ex.getStatusCode());
        assertTrue(ex.getProblemDetail().isPresent());
        ProblemDetail pd = ex.getProblemDetail().get();
        assertEquals("Internal Server Error", pd.getTitle());
        assertEquals("Connection pool exhausted", pd.getDetail());
        assertEquals(5, pd.getProperty("retryAfter"));
    }

    @Test
    @DisplayName("Standard application/json error payload with Problem Details members is also parsed")
    void standardJsonWithProblemDetailsParsed() {
        String json = """
                {
                  "title": "Bad Request",
                  "status": 400,
                  "detail": "Missing required field 'name'"
                }
                """;

        stubFor(get(urlEqualTo("/accounts/invalid"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        ProblemApiClient client = createClient();
        ClientErrorException ex = assertThrows(ClientErrorException.class, () -> client.getAccount("invalid"));

        assertTrue(ex.getProblemDetail().isPresent());
        ProblemDetail pd = ex.getProblemDetail().get();
        assertEquals(400, pd.getStatus());
        assertEquals("Bad Request", pd.getTitle());
        assertEquals("Missing required field 'name'", pd.getDetail());
    }

    @Test
    @DisplayName("BOM-prefixed problem body is still resolved on an unexpected status (RFC 8259 §8.1)")
    void bomPrefixedProblemBodyResolvesOnUnexpectedStatus() {
        String problemJson = "\uFEFF" + """
                {
                  "type": "https://example.com/probs/bom",
                  "title": "BOM Not Found",
                  "status": 404,
                  "detail": "Body carried a UTF-8 byte-order mark"
                }
                """;

        stubFor(get(urlEqualTo("/accounts/bom"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(problemJson)));

        ProblemApiClient client = createClient();
        ClientErrorException ex = assertThrows(ClientErrorException.class, () -> client.getAccount("bom"));

        assertEquals(404, ex.getStatusCode());
        assertTrue(ex.getProblemDetail().isPresent());
        ProblemDetail pd = ex.getProblemDetail().get();
        assertEquals(404, pd.getStatus());
        assertEquals("BOM Not Found", pd.getTitle());
        assertEquals("Body carried a UTF-8 byte-order mark", pd.getDetail());
        assertEquals(URI.create("https://example.com/probs/bom"), pd.getType());
    }

    @Test
    @DisplayName("BOM-prefixed problem body reaches the @OnError ProblemDetailException mapping")
    void bomPrefixedProblemBodyWithOnError() {
        String problemJson = "\uFEFF" + """
                {
                  "type": "https://example.org/errors/bom-validation",
                  "title": "Unprocessable Content",
                  "status": 422,
                  "detail": "BOM-tolerant mapping"
                }
                """;

        stubFor(get(urlEqualTo("/error-handled-bom"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(problemJson)));

        ProblemApiClient client = createClient();
        ProblemDetailException ex = assertThrows(ProblemDetailException.class, client::getWithProblemDetailBom);

        assertEquals(422, ex.getStatusCode());
        assertNotNull(ex.problemDetail());
        assertEquals("Unprocessable Content", ex.problemDetail().getTitle());
        assertEquals("BOM-tolerant mapping", ex.problemDetail().getDetail());
    }

    @Test
    @DisplayName("Declarative @OnError with errorObject = ProblemDetail.class surfaces via HandledException")
    void declarativeOnErrorWithProblemDetail() {
        String problemJson = """
                {
                  "type": "about:blank",
                  "title": "Not Found",
                  "status": 404,
                  "detail": "The requested item was not found"
                }
                """;

        stubFor(get(urlEqualTo("/error-handled-pd"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(problemJson)));

        ProblemApiClient client = createClient();
        HandledException ex = assertThrows(HandledException.class, client::getWithProblemDetail);

        assertEquals(404, ex.getStatusCode());
        assertTrue(ex.getProblemDetail().isPresent());
        ProblemDetail pd = ex.getProblemDetail().get();
        assertEquals("Not Found", pd.getTitle());
        assertEquals("The requested item was not found", pd.getDetail());
        assertEquals(pd, ex.getDetails());
    }

    @Test
    @DisplayName("Declarative @OnError with errorObject = ProblemDetailException.class throws directly")
    void declarativeOnErrorWithProblemDetailException() {
        String problemJson = """
                {
                  "type": "https://example.org/errors/validation",
                  "title": "Unprocessable Content",
                  "status": 422,
                  "detail": "Field 'email' has invalid format"
                }
                """;

        stubFor(get(urlEqualTo("/error-handled-ex"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(problemJson)));

        ProblemApiClient client = createClient();
        ProblemDetailException ex = assertThrows(ProblemDetailException.class, client::getWithProblemDetailException);

        assertEquals(422, ex.getStatusCode());
        assertNotNull(ex.problemDetail());
        assertEquals("Unprocessable Content", ex.problemDetail().getTitle());
        assertEquals("Field 'email' has invalid format", ex.problemDetail().getDetail());
    }

    @Test
    @DisplayName("Async CompletableFuture call surfaces ProblemDetail through ClientErrorException")
    void asyncCallSurfacesProblemDetail() {
        String problemJson = """
                {
                  "status": 404,
                  "title": "Not Found",
                  "detail": "Async resource missing"
                }
                """;

        stubFor(get(urlEqualTo("/async-error"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(problemJson)));

        ProblemApiClient client = createClient();
        CompletableFuture<String> future = client.getAsync();

        ExecutionException ee = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(ClientErrorException.class, ee.getCause());
        ClientErrorException cex = (ClientErrorException) ee.getCause();
        assertTrue(cex.getProblemDetail().isPresent());
        assertEquals("Async resource missing", cex.getProblemDetail().get().getDetail());
    }

    @Test
    @DisplayName("Plain text or HTML error response does not produce ProblemDetail")
    void nonJsonErrorProducesEmptyProblemDetail() {
        stubFor(get(urlEqualTo("/accounts/html"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body>502 Bad Gateway</body></html>")));

        ProblemApiClient client = createClient();
        ServerErrorException ex = assertThrows(ServerErrorException.class, () -> client.getAccount("html"));

        assertEquals(502, ex.getStatusCode());
        assertFalse(ex.getProblemDetail().isPresent());
        assertEquals("<html><body>502 Bad Gateway</body></html>", ex.getRawBody());
        assertEquals("Unexpected HTTP status 502", ex.getMessage());
    }

    @Test
    @DisplayName("Arbitrary JSON without Problem Details members and without problem media type is not treated as ProblemDetail")
    void arbitraryJsonNotTreatedAsProblemDetail() {
        stubFor(get(urlEqualTo("/accounts/arbitrary"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"username\": \"john\", \"role\": \"admin\"}")));

        ProblemApiClient client = createClient();
        ClientErrorException ex = assertThrows(ClientErrorException.class, () -> client.getAccount("arbitrary"));

        assertEquals(400, ex.getStatusCode());
        assertFalse(ex.getProblemDetail().isPresent());
        assertEquals("{\"username\": \"john\", \"role\": \"admin\"}", ex.getRawBody());
    }
}
