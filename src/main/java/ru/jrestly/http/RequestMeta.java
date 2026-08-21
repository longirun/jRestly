package ru.jrestly.http;

import ru.jrestly.annotation.RequestDefaultParam;

public record RequestMeta(
        HttpMethod httpMethod,
        String path,
        RequestType requestType,
        RequestDefaultParam[] defaultParams
) {
}
