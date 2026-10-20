package ru.jrestly.http;

import java.net.URI;
import java.util.List;

/**
 * URL assembly for the dispatch pipeline: the builder-side composition of the
 * verb path, path variables, and {@code @TargetUrl} (ADR-0002, ADR-0025,
 * ADR-0067), and the template-side query resolution (ADR-0072).
 *
 * <p>Package-private collaborator extracted from {@code HttpTemplateBuilder}
 * and {@code HttpTemplate} (ADR-0082, the wave-2 follow-up to ADR-0081). All
 * inputs are arguments — the helper holds no state, so every call allocates
 * exactly the strings/URI it returns.
 */
final class UrlComposer {

    private UrlComposer() {
    }

    /**
     * Resolves the request URL: substitutes path variables, then applies
     * {@code @TargetUrl} (path, query, and fragment merge), falling back to the
     * module base URL for relative paths.
     *
     * @param requestMeta the parsed verb metadata (path and HTTP method)
     * @param methodMetadata the parsed controller-method metadata
     * @param args the invocation arguments (nullable)
     * @param baseUrl the module's environment base URL
     * @return the composed URL, never null
     */
    static String compose(RequestMeta requestMeta, MethodMetadata methodMetadata, Object[] args, String baseUrl) {
        String path = requestMeta.path();
        if (methodMetadata.hasPathVariables() && args != null) {
            for (MethodMetadata.ParameterDescriptor p : methodMetadata.parameterDescriptors()) {
                if (p.isPathVariable() && p.index() < args.length) {
                    Object arg = args[p.index()];
                    if (arg == null) {
                        throw new IllegalArgumentException("Path variable '" + p.pathVariableName() + "' value cannot be null");
                    }
                    String substitutedValue = p.pathVariableEncoded()
                            ? arg.toString()
                            : UriEncoder.encode(arg.toString());
                    path = path.replace(p.placeholderToken(), substitutedValue);
                }
            }
        }

        String targetUrl = null;
        if (methodMetadata.hasTargetUrl() && args != null && methodMetadata.targetUrlParamIndex() < args.length) {
            Object arg = args[methodMetadata.targetUrlParamIndex()];
            if (arg == null) {
                throw new IllegalArgumentException("@TargetUrl parameter value cannot be null");
            }
            targetUrl = arg.toString();
        }

        if (targetUrl != null) {
            if (path == null || path.isEmpty()) {
                return targetUrl;
            }
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path;
            }
            int queryOrFrag = -1;
            int qIdx = targetUrl.indexOf('?');
            int hIdx = targetUrl.indexOf('#');
            if (qIdx >= 0 && hIdx >= 0) {
                queryOrFrag = Math.min(qIdx, hIdx);
            } else if (qIdx >= 0) {
                queryOrFrag = qIdx;
            } else {
                queryOrFrag = hIdx;
            }
            if (queryOrFrag >= 0) {
                String basePart = targetUrl.substring(0, queryOrFrag);
                String restPart = targetUrl.substring(queryOrFrag);
                // The verb path may carry its own static query (e.g. @Get("/items?size=10"));
                // merge it before the target's query/fragment so both survive.
                int pathQueryIdx = path.indexOf('?');
                if (pathQueryIdx < 0) {
                    return joinUrlPath(basePart, path) + restPart;
                }
                String pathPart = joinUrlPath(basePart, path.substring(0, pathQueryIdx));
                String pathQuery = path.substring(pathQueryIdx + 1);
                if (restPart.startsWith("?")) {
                    return pathPart + "?" + pathQuery + "&" + restPart.substring(1);
                }
                return pathPart + "?" + pathQuery + restPart;
            }
            return joinUrlPath(targetUrl, path);
        }

        return !path.startsWith("http")
                ? baseUrl + path
                : path;
    }

    /**
     * Joins a base URL and a path with exactly one slash at the seam.
     *
     * @param base the base URL
     * @param path the path (relative or absolute)
     * @return the joined URL
     */
    static String joinUrlPath(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        } else {
            return base + path;
        }
    }

    /**
     * Resolves the target URI by appending the request parameters to the URL,
     * inserting the query before an RFC 3986 fragment (ADR-0072).
     *
     * @param url the composed request URL
     * @param requestParams the query parameters (nullable or empty for none)
     * @return the resolved URI
     */
    static URI resolve(String url, List<Param> requestParams) {
        if (requestParams == null || requestParams.isEmpty()) {
            return URI.create(url);
        }

        int hashIndex = url.indexOf('#');
        int qIdx = url.indexOf('?');
        boolean hasQuestion = qIdx >= 0 && (hashIndex < 0 || qIdx < hashIndex);
        int baseEnd = hashIndex >= 0 ? hashIndex : url.length();

        // Single-pass buffer avoiding intermediate query strings and substrings (ADR-0072)
        StringBuilder sb = new StringBuilder(url.length() + requestParams.size() * 16 + 8);
        sb.append(url, 0, baseEnd);
        char sep = hasQuestion ? '&' : '?';

        for (int i = 0, size = requestParams.size(); i < size; i++) {
            Param param = requestParams.get(i);
            sb.append(sep);
            sep = '&';
            UriEncoder.encodeTo(param.name(), sb);
            if (param.value() != null) {
                sb.append('=');
                UriEncoder.encodeTo(param.value(), sb);
            }
        }

        if (hashIndex >= 0) {
            sb.append(url, hashIndex, url.length());
        }

        return URI.create(sb.toString());
    }

    /**
     * Appends a query string to a URL while ensuring RFC 3986 fragment placement.
     * If the URL contains a fragment ({@code #anchor}), the query is inserted
     * before the fragment.
     *
     * @param url the base URL
     * @param query the query string to append
     * @return the combined URI
     */
    static URI appendQuery(String url, String query) {
        if (query == null || query.isEmpty()) {
            return URI.create(url);
        }
        int hashIndex = url.indexOf('#');
        int qIdx = url.indexOf('?');
        boolean hasQuestion = qIdx >= 0 && (hashIndex < 0 || qIdx < hashIndex);
        int baseEnd = hashIndex >= 0 ? hashIndex : url.length();

        StringBuilder sb = new StringBuilder(url.length() + query.length() + 1);
        sb.append(url, 0, baseEnd)
                .append(hasQuestion ? '&' : '?')
                .append(query);
        if (hashIndex >= 0) {
            sb.append(url, hashIndex, url.length());
        }
        return URI.create(sb.toString());
    }
}
