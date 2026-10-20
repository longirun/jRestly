package ru.jrestly.http;

import ru.jrestly.ModuleInfo;
import ru.jrestly.annotation.CollectionFormat;
import ru.jrestly.codec.PayloadCodec;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * Assembles request bodies for one invocation: the JSON/raw payload leg
 * (byte arrays, text, files, publishers, suppliers, codec-serialized DTOs)
 * and the multipart part list (files and text parts, collection formats).
 *
 * <p>Stateless and package-private (ADR-0084); callers pass the parsed
 * metadata, invocation arguments, and effective content type explicitly.
 * Raw bindings never resolve a codec, and file sizes are probed without
 * opening the stream the transport will read (ADR-0036 Amendment #298).
 */
final class BodyComposer {

    private BodyComposer() {
    }

    /**
     * The resolved body of one request.
     *
     * @param bytes               buffered body bytes, or {@code null}
     * @param publisherSupplier   lazy publisher supplier, or {@code null}
     * @param effectiveContentType content type after raw-type defaults
     * @param knownSize           engine-known body size in bytes, or {@code null}
     */
    record BodyPayload(
            byte[] bytes,
            Supplier<? extends HttpRequest.BodyPublisher> publisherSupplier,
            String effectiveContentType,
            Long knownSize
    ) {}

    /**
     * Resolves the {@code @RequestBody} argument and maps it to a payload.
     *
     * @param moduleInfo           module configuration supplying the codec registry
     * @param methodMetadata       parsed controller method metadata
     * @param requestMeta          verb/path metadata (a GET never carries a body)
     * @param args                 invocation arguments, or {@code null}
     * @param effectiveContentType the effective request content type
     * @param isContentTypeExplicit whether the content type was declared
     * @return the resolved payload
     */
    static BodyPayload compose(ModuleInfo moduleInfo, MethodMetadata methodMetadata, RequestMeta requestMeta,
                               Object[] args, String effectiveContentType, boolean isContentTypeExplicit) {
        Object body = requestBody(methodMetadata, requestMeta, args);
        if (body == null) {
            return new BodyPayload(null, null, effectiveContentType, null);
        }

        Charset charset = parseCharset(effectiveContentType, StandardCharsets.UTF_8);

        if (body instanceof HttpRequest.BodyPublisher bp) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            return new BodyPayload(null, () -> bp, ct, null);
        }

        if (body instanceof Flow.Publisher<?> publisher) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            Flow.Publisher<ByteBuffer> adapted = adaptToByteBufferPublisher(publisher, charset);
            return new BodyPayload(null, () -> HttpRequest.BodyPublishers.fromPublisher(adapted), ct, null);
        }

        if (body instanceof InputStream inputStream) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            return new BodyPayload(null, () -> HttpRequest.BodyPublishers.ofInputStream(() -> inputStream), ct, null);
        }

        if (body instanceof Path path) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            return new BodyPayload(null, () -> {
                try {
                    return HttpRequest.BodyPublishers.ofFile(path);
                } catch (FileNotFoundException e) {
                    throw new UncheckedIOException(e);
                }
            }, ct, fileSizeBytes(path));
        }

        if (body instanceof File file) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            Path path = file.toPath();
            return new BodyPayload(null, () -> {
                try {
                    return HttpRequest.BodyPublishers.ofFile(path);
                } catch (FileNotFoundException e) {
                    throw new UncheckedIOException(e);
                }
            }, ct, fileSizeBytes(path));
        }

        if (body instanceof Supplier<?> supplier) {
            String ct = isContentTypeExplicit ? effectiveContentType : "application/octet-stream";
            Supplier<HttpRequest.BodyPublisher> publisherSupplier = () -> {
                Object supplied = supplier.get();
                if (supplied == null) {
                    return HttpRequest.BodyPublishers.noBody();
                }
                if (supplied instanceof InputStream stream) {
                    return HttpRequest.BodyPublishers.ofInputStream(() -> stream);
                }
                if (supplied instanceof HttpRequest.BodyPublisher bp) {
                    return bp;
                }
                if (supplied instanceof Flow.Publisher<?> pub) {
                    return HttpRequest.BodyPublishers.fromPublisher(adaptToByteBufferPublisher(pub, charset));
                }
                if (supplied instanceof Path p) {
                    try {
                        return HttpRequest.BodyPublishers.ofFile(p);
                    } catch (FileNotFoundException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                if (supplied instanceof File f) {
                    try {
                        return HttpRequest.BodyPublishers.ofFile(f.toPath());
                    } catch (FileNotFoundException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                if (supplied instanceof byte[] b) {
                    return HttpRequest.BodyPublishers.ofByteArray(b);
                }
                if (supplied instanceof String s) {
                    return HttpRequest.BodyPublishers.ofByteArray(s.getBytes(charset));
                }
                throw new IllegalArgumentException("Unsupported Supplier body type: " + supplied.getClass().getName());
            };
            return new BodyPayload(null, publisherSupplier, ct, null);
        }

        if (body instanceof byte[] rawBytes) {
            return new BodyPayload(rawBytes, null, effectiveContentType, null);
        }

        if (body instanceof String stringBody) {
            return new BodyPayload(stringBody.getBytes(charset), null, effectiveContentType, null);
        }

        PayloadCodec codec = moduleInfo.getCodecRegistry().resolve(effectiveContentType);
        if (codec == null) {
            throw new IllegalStateException("No PayloadCodec registered for Content-Type '"
                    + effectiveContentType + "'. Register one via JRestlyClient.builder().codec(...) "
                    + "or send a raw body type (String, byte[], InputStream, Path, File, Supplier, "
                    + "Flow.Publisher, BodyPublisher).");
        }
        byte[] bodyBytes = codec.serialize(body, effectiveContentType);
        return new BodyPayload(bodyBytes, null, effectiveContentType, null);
    }

    // ADR-0036 Amendment #298: the engine-known size of a file body, for the
    // metrics byte-accounting seam — the size is readable without touching the
    // stream the send reads from; a vanished file degrades to unknown (null).
    static Long fileSizeBytes(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }

    static Flow.Publisher<ByteBuffer> adaptToByteBufferPublisher(Flow.Publisher<?> source, Charset charset) {
        if (source == null) {
            return null;
        }
        return subscriber -> source.subscribe(new Flow.Subscriber<Object>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(Object item) {
                switch (item) {
                    case ByteBuffer bb -> subscriber.onNext(bb);
                    case byte[] b -> subscriber.onNext(ByteBuffer.wrap(b));
                    case String s -> subscriber.onNext(ByteBuffer.wrap(s.getBytes(charset)));
                    case null -> {}
                    default -> subscriber.onNext(ByteBuffer.wrap(item.toString().getBytes(charset)));
                }
            }

            @Override
            public void onError(Throwable throwable) {
                subscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
    }

    private static Object requestBody(MethodMetadata methodMetadata, RequestMeta requestMeta, Object[] args) {
        if (requestMeta.httpMethod() == HttpMethod.GET || methodMetadata.requestBodyIndex() < 0 || args == null) {
            return null;
        }
        int idx = methodMetadata.requestBodyIndex();
        return idx < args.length ? args[idx] : null;
    }

    /**
     * Assembles the multipart part list for this invocation.
     *
     * @param moduleInfo     module configuration supplying the codec registry
     * @param methodMetadata parsed controller method metadata
     * @param requestMeta    verb/path metadata carrying the request type
     * @param args           invocation arguments, or {@code null}
     * @return the parts in parameter-declaration order
     */
    static List<MultipartPart> composeParts(ModuleInfo moduleInfo, MethodMetadata methodMetadata,
                                            RequestMeta requestMeta, Object[] args) {
        if (!RequestType.MULTIPART_FORM_DATA.equals(requestMeta.requestType())) {
            throw new UnsupportedOperationException("Cannot create multipart entity");
        }

        List<MultipartPart> parts = new ArrayList<>();
        if (args == null) {
            return parts;
        }

        for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
            if (p.index() >= args.length) {
                continue;
            }
            Object arg = args[p.index()];

            if (p.isMultipartFile()) {
                for (Object value : multipartFileValues(arg)) {
                    parts.add(createFilePart(p.multipartPartName(), value, p.multipartExplicitContentType()));
                }
            } else if (p.isRequestParam()) {
                if (arg == null) {
                    continue;
                }
                String explicitContentType = p.requestParamContentType();
                // ParameterDescriptor already resolves DEFAULT/null against the method/interface default.
                CollectionFormat effectiveFormat = p.requestParamFormat();

                if (arg instanceof Map<?, ?> map) {
                    // Deliberately the raw annotation name, not the ADR-0017 effective name:
                    // for a Map, name() is an optional namespace prefix — blank sends keys as-is (Amendment #296).
                    String prefix = p.rawRequestParamName();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null) {
                            continue;
                        }
                        String partName = prefix.isEmpty()
                                ? String.valueOf(entry.getKey())
                                : prefix + "[" + entry.getKey() + "]";
                        Object val = entry.getValue();
                        if (val instanceof Collection<?> collection) {
                            List<?> nonNull = collection.stream().filter(Objects::nonNull).toList();
                            appendCollectionParts(moduleInfo, parts, partName, nonNull, explicitContentType, effectiveFormat);
                        } else if (val instanceof Object[] array) {
                            List<?> nonNull = Arrays.stream(array).filter(Objects::nonNull).toList();
                            appendCollectionParts(moduleInfo, parts, partName, nonNull, explicitContentType, effectiveFormat);
                        } else {
                            parts.add(partFromValue(moduleInfo, partName, val, explicitContentType));
                        }
                    }
                    continue;
                }

                String name = p.requestParamName();
                if (arg instanceof Collection<?> collection) {
                    List<?> nonNull = collection.stream().filter(Objects::nonNull).toList();
                    appendCollectionParts(moduleInfo, parts, name, nonNull, explicitContentType, effectiveFormat);
                } else if (arg instanceof Object[] array) {
                    List<?> nonNull = Arrays.stream(array).filter(Objects::nonNull).toList();
                    appendCollectionParts(moduleInfo, parts, name, nonNull, explicitContentType, effectiveFormat);
                } else {
                    parts.add(partFromValue(moduleInfo, name, arg, explicitContentType));
                }
            }
        }

        return parts;
    }

    private static void appendCollectionParts(ModuleInfo moduleInfo, List<MultipartPart> parts, String name,
                                              List<?> items, String explicitContentType, CollectionFormat format) {
        if (items.isEmpty()) {
            return;
        }

        // ParameterDescriptor already resolved null/DEFAULT to EXPLODE or the method-level format.
        switch (format) {
            case CSV -> {
                List<String> strItems = items.stream().map(v -> HeaderComposer.textValue(v, "parameter")).toList();
                parts.add(partFromValue(moduleInfo, name, String.join(",", strItems), explicitContentType));
            }
            case SSV -> {
                List<String> strItems = items.stream().map(v -> HeaderComposer.textValue(v, "parameter")).toList();
                parts.add(partFromValue(moduleInfo, name, String.join(" ", strItems), explicitContentType));
            }
            case TSV -> {
                List<String> strItems = items.stream().map(v -> HeaderComposer.textValue(v, "parameter")).toList();
                parts.add(partFromValue(moduleInfo, name, String.join("\t", strItems), explicitContentType));
            }
            case PIPES -> {
                List<String> strItems = items.stream().map(v -> HeaderComposer.textValue(v, "parameter")).toList();
                parts.add(partFromValue(moduleInfo, name, String.join("|", strItems), explicitContentType));
            }
            case BRACKETS -> {
                String bracketName = name + "[]";
                for (Object item : items) {
                    parts.add(partFromValue(moduleInfo, bracketName, item, explicitContentType));
                }
            }
            default -> {
                // EXPLODE and any future default-like format send one part per element.
                for (Object item : items) {
                    parts.add(partFromValue(moduleInfo, name, item, explicitContentType));
                }
            }
        }
    }

    private static MultipartPart partFromValue(ModuleInfo moduleInfo, String partName, Object value,
                                               String explicitContentType) {
        if (explicitContentType == null) {
            if (value instanceof byte[]) {
                throw new IllegalArgumentException(
                        "A byte[] multipart part requires an explicit Content-Type: declare "
                                + "@RequestParam(contentType = \"application/octet-stream\") to send raw bytes, "
                                + "or encode the bytes to text explicitly (e.g. Base64) before passing.");
            }
            return MultipartPart.text(partName, value.toString());
        }
        Charset charset = parseCharset(explicitContentType, StandardCharsets.UTF_8);
        if (value instanceof byte[] bytes) {
            return MultipartPart.part(partName, explicitContentType, bytes);
        }
        PayloadCodec codec = moduleInfo.getCodecRegistry().resolve(explicitContentType);
        if (codec != null) {
            if (value instanceof String str) {
                return MultipartPart.part(partName, explicitContentType, str.getBytes(charset));
            }
            return MultipartPart.part(partName, explicitContentType, codec.serialize(value, explicitContentType));
        }
        if (value instanceof String str) {
            return MultipartPart.part(partName, explicitContentType, str.getBytes(charset));
        }
        return MultipartPart.part(partName, explicitContentType, value.toString().getBytes(charset));
    }

    static Charset parseCharset(String contentType, Charset defaultCharset) {
        if (contentType == null || contentType.isEmpty()) {
            return defaultCharset;
        }
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx >= 0) {
            String csName = contentType.substring(idx + 8).trim();
            int semi = csName.indexOf(';');
            if (semi >= 0) {
                csName = csName.substring(0, semi).trim();
            }
            if (csName.startsWith("\"") && csName.endsWith("\"") && csName.length() >= 2) {
                csName = csName.substring(1, csName.length() - 1);
            }
            try {
                return Charset.forName(csName);
            } catch (Exception ignored) {
                return defaultCharset;
            }
        }
        return defaultCharset;
    }

    private static MultipartPart createFilePart(String partName, Object arg, String explicitContentType) {
        // multipartFileValues never yields null, so the switch can assume a concrete argument.
        return switch (arg) {
            case FileContent content -> MultipartPart.file(partName, content.fileName(), explicitContentType,
                    content.content(), content.contentLength());
            case byte[] ignored -> throw new IllegalArgumentException(
                    "A bare byte[] cannot be a multipart file: the wire file name cannot be derived from it. "
                            + "Wrap in-memory bytes in FileContent.of(fileName, bytes)");
            case File file -> createFilePartFromPath(partName, file.toPath(), explicitContentType);
            case Path p -> createFilePartFromPath(partName, p, explicitContentType);
            case String str -> createFilePartFromPath(partName, Path.of(str), explicitContentType);
            // Arrays/collections are flattened by multipartFileValues before reaching this point.
            default -> throw new IllegalArgumentException(
                    "Unsupported multipart file argument of type " + arg.getClass().getName()
                            + ". Expected Path, File, String, or FileContent");
        };
    }

    private static MultipartPart createFilePartFromPath(String partName, Path path, String explicitContentType) {
        if (path.getFileName() == null) {
            throw new IllegalArgumentException("Path must have a file name: " + path);
        }
        return MultipartPart.file(partName, path.getFileName().toString(), explicitContentType, path, null);
    }

    // Unpacks collections, arrays, and varargs into individual part values
    private static List<?> multipartFileValues(Object arg) {
        return switch (arg) {
            case null -> List.of();
            case Collection<?> collection -> collection.stream().filter(Objects::nonNull).toList();
            case Object[] array -> Arrays.stream(array).filter(Objects::nonNull).toList();
            default -> List.of(arg);
        };
    }
}
