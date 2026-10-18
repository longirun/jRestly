package ru.jrestly.fixtures;

import ru.jrestly.annotation.*;

public interface RedirectMethodController {

    @Get("/api/redirect-nofollow")
    String getWithoutFollowRedirects();

    @Post("/api/redirect-post-nofollow")
    String postWithoutFollowRedirects(@RequestBody TestDto item);

    @FollowRedirects(count = 2)
    @Put("/api/redirect-put")
    TestDto putWithRedirect(@RequestBody TestDto item);

    @FollowRedirects(count = 2)
    @Patch("/api/redirect-patch")
    TestDto patchWithRedirect(@RequestBody TestDto item);

    @FollowRedirects(count = 2)
    @Get("/api/redirect-relative")
    String getWithRelativeRedirectLocation();

    @FollowRedirects(count = 2)
    @Get("/api/same-origin")
    String getSameOriginRedirect();

    @FollowRedirects(count = 1)
    @Get("/api/redirect")
    String getWithExplicitAuthOverride(@RequestHeader(name = "Authorization") String token);
}
