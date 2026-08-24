package ru.jrestly.json;

public final class JsonCodecResolver {

    private JsonCodecResolver() {
    }

    public static JsonCodec resolve() {
        if (isOnClasspath("com.fasterxml.jackson.databind.ObjectMapper")) {
            return JacksonCodec.defaults();
        }

        throw new IllegalStateException("No JSON codec found on the classpath. "
                + "Add jackson-databind as a runtime dependency "
                + "or set one explicitly via JRestlyClient.builder().jsonCodec(...)");
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
