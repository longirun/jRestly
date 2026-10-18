package ru.jrestly.fixtures;

import ru.jrestly.annotation.*;

/**
 * Redirect behavioral fixture: no-follow and follow budgets per verb,
 * relative {@code Location}, same-origin hops, and the explicit
 * {@code Authorization} override that must survive a cross-origin hop
 * (ADR-0002, Amendment #253).
 */
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
