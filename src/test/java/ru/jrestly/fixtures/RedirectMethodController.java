package ru.jrestly.fixtures;

import ru.jrestly.annotation.*;

public interface RedirectMethodController {

    @Get(path = "/api/redirect-nofollow")
    String getWithoutFollowRedirects();

    @Post(path = "/api/redirect-post-nofollow")
    String postWithoutFollowRedirects(@RequestBody TestDto item);

    @FollowRedirects(count = 2)
    @Put(path = "/api/redirect-put")
    TestDto putWithRedirect(@RequestBody TestDto item);

    @FollowRedirects(count = 2)
    @Patch(path = "/api/redirect-patch")
    TestDto patchWithRedirect(@RequestBody TestDto item);

    @FollowRedirects(count = 2)
    @Get(path = "/api/redirect-relative")
    String getWithRelativeRedirectLocation();

    @FollowRedirects(count = 2)
    @Get(path = "/api/same-origin")
    String getSameOriginRedirect();
}
