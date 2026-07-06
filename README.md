# jRestly

[![](https://jitpack.io/v/longirun/jRestly.svg)](https://jitpack.io/#longirun/jRestly)

A declarative HTTP client for Java. Define an interface, get a working client — no code generation, just a dynamic proxy over Apache HttpClient + Jackson.

> **Status:** early development. v0.1.0 is the initial publish. A v2 rewrite on `java.net.http.HttpClient` with pluggable JSON/cookie SPI, first-class auth flows, and stricter RFC compliance is planned. See `TODO.md`.

## Features

- Declarative interface-based API via `java.lang.reflect.Proxy`
- HTTP methods: `@Get`, `@Post`, `@Put`, `@Patch`, `@Delete`
- Path variables (`@PathVariable` + `${var}`), query params (`@RequestParam`, `@RequestDefaultParam`), headers (`@RequestHeader`), request body (`@RequestBody`)
- Content types: `application/json` (default), `application/x-www-form-urlencoded`, `multipart/form-data`
- Per-method typed error mapping: `@OnError(statuses = {…}, errorObject = X.class)` throws `HandledException` with the deserialized body
- Per-method redirect chasing: `@FollowRedirects(count = N)`
- Auth state machine: `@Anonymous` (skip auth refresh), `@Authorization` (this is the login call), `@SetAuthDetails(headerName = …)` (capture token from response, reuse on subsequent calls)
- Apache HttpClient 4 + Jackson 2 (configurable `ObjectMapper`)

## Installation

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.longirun:jRestly:v0.1.0")
}
```

### Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.longirun:jRestly:v0.1.0'
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.longirun</groupId>
    <artifactId>jRestly</artifactId>
    <version>v0.1.0</version>
</dependency>
```

> Requires Java 21+ (compiled with `-parameters` flag).

## Quick start

Define a controller interface for the API you want to call:

```java
import ru.jrestly.annotation.*;
import ru.jrestly.http.RequestType;
import java.util.List;

public interface ItemApi {

    @Get(path = "/api/items/${id}")
    Item getItem(@PathVariable(name = "id") long id);

    @Get(path = "/api/items")
    List<Item> listItems(@RequestParam(name = "page") int page);

    @Post(path = "/api/items")
    Item createItem(@RequestBody Item item);

    @Delete(path = "/api/items/${id}")
    void deleteItem(@PathVariable(name = "id") long id);

    @Get(path = "/api/items/${id}")
    @OnError(statuses = {404}, errorObject = ApiError.class)
    Item getItemOrThrow(@PathVariable(name = "id") long id);
}
```

Build a client and use it:

```java
JRestlyClient client = JRestlyClient.builder()
        .baseUrl("https://api.example.com")
        .connectTimeout(10_000)
        .socketTimeout(30_000)
        .build();

ItemApi api = client.get(ItemApi.class);

Item created = api.createItem(new Item("widget", 42));
Item fetched = api.getItem(created.id());
List<Item> page = api.listItems(1);
```

## License

Apache License 2.0. See [LICENSE](LICENSE).

Copyright 2026 Anton Sinyagovsky.
