package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.annotation.RequestParam;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour pins for {@link BodyComposer}: raw and codec body legs plus the
 * multipart part assembly extracted from the builder (ADR-0084). The tests
 * exercise the collaborator directly, without a network round trip.
 */
class BodyComposerTest {

    private final SimpleModuleInfo module = SimpleModuleInfo.builder()
            .baseUrl("https://api.example.com")
            .build();

    record Dto(String name, int age) {
    }

    interface BodyApi {
        @Post("/body")
        String post(@RequestBody Object body);

        @Get("/body")
        String get(@RequestBody Object body);

        @Post("/no-body")
        String noBody();
    }

    interface MultipartFilesApi {
        @Post(value = "/files", requestType = RequestType.MULTIPART_FORM_DATA)
        String upload(@MultipartFormFile(partName = "file") Object file);

        @Post(value = "/many", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadMany(@MultipartFormFile(partName = "file") List<Object> files);
    }

    interface MultipartPartsApi {
        @Post(value = "/parts", requestType = RequestType.MULTIPART_FORM_DATA)
        String parts(
                @RequestParam(name = "csv", format = CollectionFormat.CSV) List<String> csv,
                @RequestParam(name = "brackets", format = CollectionFormat.BRACKETS) List<String> brackets,
                @RequestParam(name = "explode") List<String> explode,
                @RequestParam Map<String, Object> map);

        @Post(value = "/raw", requestType = RequestType.MULTIPART_FORM_DATA)
        String raw(@RequestParam(name = "raw", contentType = "application/octet-stream") byte[] raw);

        @Post(value = "/bare", requestType = RequestType.MULTIPART_FORM_DATA)
        String bare(@RequestParam(name = "raw") byte[] raw);

        @Post(value = "/typed", requestType = RequestType.MULTIPART_FORM_DATA)
        String typed(
                @RequestParam(name = "json", contentType = "application/json") String json,
                @RequestParam(name = "jsonNumber", contentType = "application/json") Integer jsonNumber,
                @RequestParam(name = "text", contentType = "text/plain") Integer number,
                @RequestParam(name = "textString", contentType = "text/plain") String textString,
                @RequestParam(name = "explode") List<String> explode);

        @Post(value = "/shapes", requestType = RequestType.MULTIPART_FORM_DATA)
        String shapes(
                @RequestParam(name = "ssv", format = CollectionFormat.SSV) List<String> ssv,
                @RequestParam(name = "tsv", format = CollectionFormat.TSV) List<String> tsv,
                @RequestParam(name = "pipes", format = CollectionFormat.PIPES) List<String> pipes,
                @RequestParam(name = "array") String[] array,
                @RequestParam(name = "empty") List<String> empty,
                @RequestParam(name = "plain") String plain,
                @RequestParam(name = "ns") Map<String, Object> prefixed,
                String ignored);
    }

    private static MethodMetadata metadata(Method method, Class<?> owner) {
        return MethodMetadata.of(method, owner);
    }

    private static BodyComposer.BodyPayload postBody(Method method, Object argument) {
        SimpleModuleInfo module = SimpleModuleInfo.builder().baseUrl("https://api.example.com").build();
        MethodMetadata md = metadata(method, BodyApi.class);
        return BodyComposer.compose(module, md, md.requestMeta(), new Object[]{argument}, "application/json", false);
    }

    private static Object delivered(HttpRequest.BodyPublisher publisher) {
        List<byte[]> chunks = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                chunks.add(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        int total = chunks.stream().mapToInt(c -> c.length).sum();
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Payload leg
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a null body, a GET body and a method without @RequestBody resolve to no payload")
    void nullBodyResolvesToNoPayload() throws Exception {
        BodyComposer.BodyPayload nullPayload = postBody(BodyApi.class.getMethod("post", Object.class), null);
        assertNull(nullPayload.bytes());
        assertNull(nullPayload.publisherSupplier());
        assertEquals("application/json", nullPayload.effectiveContentType());

        BodyComposer.BodyPayload getPayload = postBody(BodyApi.class.getMethod("get", Object.class), "ignored");
        assertNull(getPayload.bytes());
        assertNull(getPayload.publisherSupplier());

        MethodMetadata md = metadata(BodyApi.class.getMethod("noBody"), BodyApi.class);
        BodyComposer.BodyPayload absent = BodyComposer.compose(module, md, md.requestMeta(), new Object[0],
                "application/json", false);
        assertNull(absent.bytes());
        assertNull(absent.publisherSupplier());

        MethodMetadata postMd = metadata(BodyApi.class.getMethod("post", Object.class), BodyApi.class);
        assertNull(BodyComposer.compose(module, postMd, postMd.requestMeta(),
                null, "application/json", false).publisherSupplier());
        assertNull(BodyComposer.compose(module, postMd, postMd.requestMeta(),
                new Object[0], "application/json", false).publisherSupplier());
    }

    @Test
    @DisplayName("String bodies honor the declared charset; byte[] bodies pass through")
    void textAndByteBodies() throws Exception {
        Method post = BodyApi.class.getMethod("post", Object.class);
        MethodMetadata md = metadata(post, BodyApi.class);
        BodyComposer.BodyPayload text = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{"héllo"}, "text/plain; charset=ISO-8859-1", true);
        assertArrayEquals("héllo".getBytes(StandardCharsets.ISO_8859_1), text.bytes());
        assertEquals("text/plain; charset=ISO-8859-1", text.effectiveContentType());

        byte[] raw = {1, 2, 3};
        BodyComposer.BodyPayload bytes = postBody(post, raw);
        assertArrayEquals(raw, bytes.bytes());
        assertNull(bytes.publisherSupplier());
    }

    @Test
    @DisplayName("DTOs are serialized by the registered codec; a missing codec fails fast")
    void dtoSerialization() throws Exception {
        Method post = BodyApi.class.getMethod("post", Object.class);
        MethodMetadata md = metadata(post, BodyApi.class);
        BodyComposer.BodyPayload payload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{new Dto("Anton", 42)}, "application/json", true);
        String json = new String(payload.bytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"name\":\"Anton\""));
        assertTrue(json.contains("\"age\":42"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BodyComposer.compose(module, md, md.requestMeta(), new Object[]{new Dto("x", 1)},
                        "application/xml", true));
        assertTrue(failure.getMessage().contains("No PayloadCodec registered"));
    }

    @Test
    @DisplayName("InputStream, Path and File bodies stay lazy; file sizes are probed for metrics")
    void streamingAndFileBodies() throws Exception {
        Method post = BodyApi.class.getMethod("post", Object.class);
        MethodMetadata md = metadata(post, BodyApi.class);

        InputStream stream = new ByteArrayInputStream("stream".getBytes(StandardCharsets.UTF_8));
        BodyComposer.BodyPayload streamPayload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{stream}, "application/json", false);
        assertEquals(-1, streamPayload.publisherSupplier().get().contentLength());
        assertEquals("application/octet-stream", streamPayload.effectiveContentType());
        assertEquals("stream", delivered(streamPayload.publisherSupplier().get()));

        Path file = Files.createTempFile("body-composer-", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, "file-body");
        BodyComposer.BodyPayload pathPayload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{file}, "application/json", false);
        assertEquals(Files.size(file), pathPayload.knownSize());
        assertEquals("file-body", delivered(pathPayload.publisherSupplier().get()));

        BodyComposer.BodyPayload filePayload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{file.toFile()}, "application/json", false);
        assertEquals(Files.size(file), filePayload.knownSize());
        assertEquals("file-body", delivered(filePayload.publisherSupplier().get()));
    }

    @Test
    @DisplayName("top-level publishers and lazy bodies honor an explicit content type")
    void explicitContentTypes() throws Exception {
        Method post = BodyApi.class.getMethod("post", Object.class);
        MethodMetadata md = metadata(post, BodyApi.class);

        HttpRequest.BodyPublisher direct = HttpRequest.BodyPublishers.ofString("direct");
        BodyComposer.BodyPayload directPayload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{direct}, "text/plain", true);
        assertEquals("text/plain", directPayload.effectiveContentType());
        assertEquals(direct, directPayload.publisherSupplier().get());

        Flow.Publisher<Object> source = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            subscriber.onNext("flow");
            subscriber.onComplete();
        };
        BodyComposer.BodyPayload flowPayload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{source}, "application/json", false);
        assertEquals("application/octet-stream", flowPayload.effectiveContentType());
        assertEquals("flow", delivered(flowPayload.publisherSupplier().get()));

        InputStream stream = new ByteArrayInputStream("explicit".getBytes(StandardCharsets.UTF_8));
        BodyComposer.BodyPayload streamPayload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{stream}, "application/octet-stream", true);
        assertEquals("application/octet-stream", streamPayload.effectiveContentType());

        Path file = Files.createTempFile("body-explicit-", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, "explicit-file");
        assertEquals("text/plain", BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{file}, "text/plain", true).effectiveContentType());
        assertEquals("text/plain", BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{file.toFile()}, "text/plain", true).effectiveContentType());

        BodyComposer.BodyPayload publisherFallback = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{HttpRequest.BodyPublishers.ofString("fallback")}, "application/json", false);
        assertEquals("application/octet-stream", publisherFallback.effectiveContentType());
    }

    @Test
    @DisplayName("missing files stay lazy and surface as UncheckedIOException")
    void missingFilesFailLazily() throws Exception {
        Method post = BodyApi.class.getMethod("post", Object.class);
        MethodMetadata md = metadata(post, BodyApi.class);
        Path missing = Path.of("/tmp/definitely-missing-body-composer.txt");

        BodyComposer.BodyPayload pathPayload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{missing}, "application/json", false);
        assertNull(pathPayload.knownSize());
        assertThrows(java.io.UncheckedIOException.class, () -> pathPayload.publisherSupplier().get());

        BodyComposer.BodyPayload filePayload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{missing.toFile()}, "application/json", false);
        assertNull(filePayload.knownSize());
        assertThrows(java.io.UncheckedIOException.class, () -> filePayload.publisherSupplier().get());

        BodyComposer.BodyPayload supplierPath = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{(Supplier<?>) () -> missing}, "application/json", false);
        assertThrows(java.io.UncheckedIOException.class, () -> supplierPath.publisherSupplier().get());
        BodyComposer.BodyPayload supplierFile = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{(Supplier<?>) () -> missing.toFile()}, "application/json", false);
        assertThrows(java.io.UncheckedIOException.class, () -> supplierFile.publisherSupplier().get());
    }

    @Test
    @DisplayName("a Flow.Publisher body is adapted to ByteBuffers using the payload charset")
    void flowPublisherIsAdapted() throws Exception {
        Method post = BodyApi.class.getMethod("post", Object.class);
        MethodMetadata md = metadata(post, BodyApi.class);
        Flow.Publisher<Object> source = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            subscriber.onNext(ByteBuffer.wrap("a".getBytes(StandardCharsets.UTF_8)));
            // Raw byte arrays pass through untouched; the collector decodes UTF-8.
            subscriber.onNext("é".getBytes(StandardCharsets.UTF_8));
            subscriber.onNext(null);
            subscriber.onNext("z");
            subscriber.onComplete();
        };
        BodyComposer.BodyPayload payload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{source}, "text/plain; charset=ISO-8859-1", true);
        assertNull(payload.bytes());
        assertEquals("aéz", delivered(payload.publisherSupplier().get()));
        assertEquals("text/plain; charset=ISO-8859-1", payload.effectiveContentType());
    }

    @Test
    @DisplayName("Supplier bodies are replayed per subscription and cover every supported type")
    void supplierBodies() throws Exception {
        Method post = BodyApi.class.getMethod("post", Object.class);
        MethodMetadata md = metadata(post, BodyApi.class);

        BodyComposer.BodyPayload nullSupplier = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{(Supplier<?>) () -> null}, "application/json", false);
        assertEquals(0, nullSupplier.publisherSupplier().get().contentLength());

        BodyComposer.BodyPayload explicitSupplier = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{(Supplier<?>) () -> "text"}, "text/plain", true);
        assertEquals("text/plain", explicitSupplier.effectiveContentType());

        assertEquals("text", supplied(md, (Supplier<?>) () -> "text"));
        assertEquals("bytes", supplied(md, (Supplier<?>) () -> "bytes".getBytes(StandardCharsets.UTF_8)));
        assertEquals("stream", supplied(md, (Supplier<?>) () -> new ByteArrayInputStream("stream".getBytes(StandardCharsets.UTF_8))));

        HttpRequest.BodyPublisher direct = HttpRequest.BodyPublishers.ofString("direct");
        BodyComposer.BodyPayload directPayload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{(Supplier<?>) () -> direct}, "application/json", false);
        assertEquals(direct, directPayload.publisherSupplier().get());

        Path file = Files.createTempFile("supplier-body-", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, "path");
        assertEquals("path", supplied(md, (Supplier<?>) () -> file));
        assertEquals("path", supplied(md, (Supplier<?>) () -> file.toFile()));

        Flow.Publisher<Object> flow = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            subscriber.onNext("flow");
            subscriber.onComplete();
        };
        assertEquals("flow", supplied(md, (Supplier<?>) () -> flow));

        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> supplied(md, (Supplier<?>) () -> 42));
        assertTrue(unsupported.getMessage().contains("Unsupported Supplier body type"));
    }

    private String supplied(MethodMetadata md, Supplier<?> supplier) {
        BodyComposer.BodyPayload payload = BodyComposer.compose(module, md, md.requestMeta(),
                new Object[]{supplier}, "application/json", false);
        return (String) delivered(payload.publisherSupplier().get());
    }

    // -------------------------------------------------------------------------
    // Multipart leg
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("multipart assembly carries files, CSV/BRACKETS/EXPLODE parts and map keys")
    void multipartParts() throws Exception {
        Method method = MultipartPartsApi.class.getMethod("parts", List.class, List.class, List.class, Map.class);
        MethodMetadata md = metadata(method, MultipartPartsApi.class);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", "value");
        map.put(null, "ignored");
        map.put("skip", null);
        List<MultipartPart> parts = BodyComposer.composeParts(module, md, md.requestMeta(),
                new Object[]{List.of("a", "b"), List.of("c", "d"), List.of("e", "f"), map});

        assertTrue(parts.stream().anyMatch(p -> "csv".equals(p.name()) && "a,b".equals(text(p))));
        assertEquals(2, parts.stream().filter(p -> "brackets[]".equals(p.name())).count());
        assertEquals(2, parts.stream().filter(p -> "explode".equals(p.name())).count());
        assertTrue(parts.stream().anyMatch(p -> "key".equals(p.name()) && "value".equals(text(p))));
        assertTrue(BodyComposer.composeParts(module, md, md.requestMeta(), null).isEmpty());
        assertTrue(BodyComposer.composeParts(module, md, md.requestMeta(), new Object[0]).isEmpty());
    }

    @Test
    @DisplayName("explicit multipart content types use codecs, charset fallbacks and DEFAULT format")
    void multipartTypedParts() throws Exception {
        Method method = MultipartPartsApi.class.getMethod("typed", String.class, Integer.class,
                Integer.class, String.class, List.class);
        MethodMetadata md = metadata(method, MultipartPartsApi.class);
        List<MultipartPart> parts = BodyComposer.composeParts(module, md, md.requestMeta(),
                new Object[]{"{\"a\":1}", 7, 42, "plain", List.of("x", "y")});

        MultipartPart json = parts.stream().filter(p -> "json".equals(p.name())).findFirst().orElseThrow();
        assertEquals("application/json", json.contentType());
        assertEquals("{\"a\":1}", text(json));
        MultipartPart jsonNumber = parts.stream().filter(p -> "jsonNumber".equals(p.name())).findFirst().orElseThrow();
        assertEquals("application/json", jsonNumber.contentType());
        assertEquals("7", text(jsonNumber));
        MultipartPart number = parts.stream().filter(p -> "text".equals(p.name())).findFirst().orElseThrow();
        assertEquals("text/plain", number.contentType());
        assertEquals("42", text(number));
        MultipartPart textString = parts.stream().filter(p -> "textString".equals(p.name())).findFirst().orElseThrow();
        assertEquals("text/plain", textString.contentType());
        assertEquals("plain", text(textString));
        assertEquals(2, parts.stream().filter(p -> "explode".equals(p.name())).count());
    }

    @Test
    @DisplayName("multipart shapes cover SSV/TSV/PIPES, arrays, empty lists, nulls and prefixed maps")
    void multipartShapes() throws Exception {
        Method method = MultipartPartsApi.class.getMethod("shapes", List.class, List.class, List.class,
                String[].class, List.class, String.class, Map.class, String.class);
        MethodMetadata md = metadata(method, MultipartPartsApi.class);
        Map<String, Object> prefixed = new LinkedHashMap<>();
        prefixed.put("many", List.of("a", "b"));
        prefixed.put("array", new String[]{"c"});
        prefixed.put("one", "d");
        List<MultipartPart> parts = BodyComposer.composeParts(module, md, md.requestMeta(),
                new Object[]{List.of("a", "b"), List.of("a", "b"), List.of("a", "b"),
                        new String[]{"a", "b"}, List.of(), null, prefixed, "ignored"});

        assertTrue(parts.stream().anyMatch(p -> "ssv".equals(p.name()) && "a b".equals(text(p))));
        assertTrue(parts.stream().anyMatch(p -> "tsv".equals(p.name()) && "a\tb".equals(text(p))));
        assertTrue(parts.stream().anyMatch(p -> "pipes".equals(p.name()) && "a|b".equals(text(p))));
        assertEquals(2, parts.stream().filter(p -> "array".equals(p.name())).count());
        assertEquals(2, parts.stream().filter(p -> "ns[many]".equals(p.name())).count());
        assertTrue(parts.stream().anyMatch(p -> "ns[array]".equals(p.name()) && "c".equals(text(p))));
        assertTrue(parts.stream().anyMatch(p -> "ns[one]".equals(p.name()) && "d".equals(text(p))));
    }

    @Test
    @DisplayName("multipart file arguments accept FileContent, File, Path, String; null sequences are empty")
    void multipartFiles() throws Exception {
        Method upload = MultipartFilesApi.class.getMethod("upload", Object.class);
        MethodMetadata md = metadata(upload, MultipartFilesApi.class);
        Path file = Files.createTempFile("part-", ".txt");
        file.toFile().deleteOnExit();

        assertEquals("part-" + file.getFileName().toString().substring(5),
                BodyComposer.composeParts(module, md, md.requestMeta(), new Object[]{file}).getFirst().filename());
        assertTrue(BodyComposer.composeParts(module, md, md.requestMeta(),
                new Object[]{file.toFile()}).getFirst().filename().endsWith(".txt"));
        assertTrue(BodyComposer.composeParts(module, md, md.requestMeta(),
                new Object[]{file.toString()}).getFirst().filename().endsWith(".txt"));
        assertTrue(BodyComposer.composeParts(module, md, md.requestMeta(),
                new Object[]{FileContent.of("custom.bin", new byte[]{1})}).getFirst().filename().equals("custom.bin"));
        assertTrue(BodyComposer.composeParts(module, md, md.requestMeta(), new Object[]{null}).isEmpty());

        Method many = MultipartFilesApi.class.getMethod("uploadMany", List.class);
        MethodMetadata manyMd = metadata(many, MultipartFilesApi.class);
        assertEquals(2, BodyComposer.composeParts(module, manyMd, manyMd.requestMeta(),
                new Object[]{List.of(file, FileContent.of("b.bin", new byte[]{2}))}).size());
        assertEquals(2, BodyComposer.composeParts(module, md, md.requestMeta(),
                new Object[]{new Object[]{file, FileContent.of("c.bin", new byte[]{3})}}).size());
    }

    @Test
    @DisplayName("bare byte[] and unsupported multipart file values fail fast")
    void multipartFileFailures() throws Exception {
        Method upload = MultipartFilesApi.class.getMethod("upload", Object.class);
        MethodMetadata md = metadata(upload, MultipartFilesApi.class);
        IllegalArgumentException bare = assertThrows(IllegalArgumentException.class,
                () -> BodyComposer.composeParts(module, md, md.requestMeta(), new Object[]{new byte[]{1}}));
        assertTrue(bare.getMessage().contains("bare byte[]"));

        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> BodyComposer.composeParts(module, md, md.requestMeta(), new Object[]{42L}));
        assertTrue(unsupported.getMessage().contains("Unsupported multipart file argument"));

        IllegalArgumentException root = assertThrows(IllegalArgumentException.class,
                () -> BodyComposer.composeParts(module, md, md.requestMeta(), new Object[]{Path.of("/")}));
        assertTrue(root.getMessage().contains("Path must have a file name"));
    }

    @Test
    @DisplayName("raw multipart parts require an explicit content type; text parts use the declared charset")
    void multipartExplicitContentType() throws Exception {
        Method raw = MultipartPartsApi.class.getMethod("raw", byte[].class);
        MethodMetadata rawMd = metadata(raw, MultipartPartsApi.class);
        MultipartPart part = BodyComposer.composeParts(module, rawMd, rawMd.requestMeta(),
                new Object[]{new byte[]{1, 2}}).getFirst();
        assertEquals("application/octet-stream", part.contentType());
        assertArrayEquals(new byte[]{1, 2}, (byte[]) part.content());

        Method bare = MultipartPartsApi.class.getMethod("bare", byte[].class);
        MethodMetadata bareMd = metadata(bare, MultipartPartsApi.class);
        assertThrows(IllegalArgumentException.class,
                () -> BodyComposer.composeParts(module, bareMd, bareMd.requestMeta(), new Object[]{new byte[]{1}}));
    }

    @Test
    @DisplayName("multipart assembly rejects non-multipart request types")
    void multipartRequiresMultipartType() throws Exception {
        Method post = BodyApi.class.getMethod("post", Object.class);
        MethodMetadata md = metadata(post, BodyApi.class);
        assertThrows(UnsupportedOperationException.class,
                () -> BodyComposer.composeParts(module, md, md.requestMeta(), new Object[]{"body"}));
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseCharset handles null, empty, quoted, delimited and unknown charsets")
    void parseCharsetMatrix() {
        assertEquals(StandardCharsets.UTF_8, BodyComposer.parseCharset(null, StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8, BodyComposer.parseCharset("", StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.ISO_8859_1,
                BodyComposer.parseCharset("text/plain; charset=ISO-8859-1", StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_16,
                BodyComposer.parseCharset("text/plain; charset=\"UTF-16\"; other=1", StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8,
                BodyComposer.parseCharset("text/plain; charset=NON_EXISTENT_CS", StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8,
                BodyComposer.parseCharset("text/plain; charset=\"", StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8,
                BodyComposer.parseCharset("text/plain; charset=\"UTF-16", StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("adaptToByteBufferPublisher forwards null, data, errors and completion")
    void adaptPublisherContract() {
        assertNull(BodyComposer.adaptToByteBufferPublisher(null, StandardCharsets.UTF_8));

        RuntimeException failure = new RuntimeException("boom");
        Flow.Publisher<Object> source = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            subscriber.onError(failure);
        };
        List<Throwable> errors = new ArrayList<>();
        BodyComposer.adaptToByteBufferPublisher(source, StandardCharsets.UTF_8).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                assertNotNull(subscription);
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBuffer item) {
            }

            @Override
            public void onError(Throwable throwable) {
                errors.add(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        assertEquals(List.of(failure), errors);
        assertInstanceOf(Flow.Publisher.class, BodyComposer.adaptToByteBufferPublisher(subscriber -> {
        }, StandardCharsets.UTF_8));

        Flow.Publisher<Object> values = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            subscriber.onNext(42);
            subscriber.onComplete();
        };
        assertEquals("42", delivered(HttpRequest.BodyPublishers.fromPublisher(
                BodyComposer.adaptToByteBufferPublisher(values, StandardCharsets.UTF_8))));
    }

    private static String text(MultipartPart part) {
        return new String((byte[]) part.content(), StandardCharsets.UTF_8);
    }
}
