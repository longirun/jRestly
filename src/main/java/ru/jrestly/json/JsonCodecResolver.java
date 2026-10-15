package ru.jrestly.json;

import java.util.function.Predicate;

/**
 * Resolves the {@link JsonCodec} used by a client.
 *
 * <p>An explicit {@code jsonCodec(...)} builder setting always wins; without
 * one, the first library found on the classpath provides its defaults codec,
 * probing in fixed order jackson &rarr; gson &rarr; moshi (ADR-0007, Aspect 1).
 * When none of the supported libraries is found, resolves to {@link NoOpJsonCodec}
 * (ADR-0045), allowing pure zero-dependency standalone operation for raw string,
 * byte array, streaming, and void requests.
 * Each adapter class is only loaded after its probe succeeds, so a classpath
 * that lacks a library never touches the corresponding adapter.
 */
public final class JsonCodecResolver {

    private JsonCodecResolver() {
    }

    /**
     * Probes the real classpath and returns the first matching codec, or
     * {@link NoOpJsonCodec#instance()} if no supported library is present.
     *
     * @return the defaults codec of the first supported library found on the classpath
     * (jackson &rarr; gson &rarr; moshi), or {@link NoOpJsonCodec#instance()}
     */
    public static JsonCodec resolve() {
        return resolve(JsonCodecResolver::isOnClasspath);
    }

    /**
     * Test seam (ADR-0007, Aspect 6): resolves the codec against a supplied
     * classpath probe instead of the real classpath, so each probe
     * combination is testable without {@code URLClassLoader} machinery.
     */
    static JsonCodec resolve(Predicate<String> classpathProbe) {
        if (classpathProbe.test("com.fasterxml.jackson.databind.ObjectMapper")) {
            return JacksonCodec.defaults();
        }
        if (classpathProbe.test("com.google.gson.Gson")) {
            return GsonCodec.defaults();
        }
        if (classpathProbe.test("com.squareup.moshi.Moshi")) {
            return MoshiCodec.defaults();
        }

        return NoOpJsonCodec.instance();
    }

    private static boolean isOnClasspath(String fqn) {
        try {
            Class.forName(fqn, false, JsonCodecResolver.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
