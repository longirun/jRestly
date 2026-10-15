package ru.jrestly.http;

import ru.jrestly.annotation.RequestDefaultParam;

/**
 * Request metadata resolved from a controller method's HTTP verb annotation:
 * the HTTP method, the path, the request content type, and the method's
 * static default parameters.
 *
 * <p>Assembled per method by the {@link HttpTemplateBuilder} from the
 * {@code @Get}/{@code @Post}/{@code @Put}/{@code @Patch}/{@code @Delete}
 * annotation: {@code path} is the declared path (relative paths are
 * prefixed with the environment base URL at request time), {@code
 * requestType} is {@code application/json} for {@code @Get} and the
 * annotation's declared value otherwise, and {@code defaultParams}
 * carries the annotation's {@code @RequestDefaultParam} entries.
 *
 * @param httpMethod the HTTP method of the verb annotation
 * @param path the declared path template, possibly containing {@code ${name}} placeholders
 * @param requestType the request content type
 * @param defaultParams the annotation's static default parameters, empty when none are declared
 */
record RequestMeta(
        HttpMethod httpMethod,
        String path,
        RequestType requestType,
        RequestDefaultParam[] defaultParams
) {
}
