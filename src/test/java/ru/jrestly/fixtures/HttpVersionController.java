package ru.jrestly.fixtures;

import ru.jrestly.annotation.FollowRedirects;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.HttpVersion;

import java.net.http.HttpClient;

public interface HttpVersionController {

    @HttpVersion(HttpClient.Version.HTTP_1_1)
    @Get(path = "/api/version-pinned")
    String getPinnedToHttp11();

    @Get(path = "/api/version-unpinned")
    String getUnpinned();

    @HttpVersion(HttpClient.Version.HTTP_1_1)
    @FollowRedirects(count = 1)
    @Get(path = "/api/version-redirect")
    String getRedirectPinnedToHttp11();
}
