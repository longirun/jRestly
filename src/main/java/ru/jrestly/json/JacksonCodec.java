package ru.jrestly.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;

public class JacksonCodec implements JsonCodec {

    private final ObjectMapper mapper;

    public JacksonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static JacksonCodec defaults() {
        return new JacksonCodec(createDefault());
    }

    @Override
    public <T> T deserialize(String payload, Type type) {
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        try {
            return mapper.readValue(payload, javaType);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize JSON into " + type.getTypeName(), e);
        }
    }

    @Override
    public String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize " + (value != null ? value.getClass().getName() : "null") + " to JSON", e);
        }
    }

    @Override
    public String prettyPrint(String payload) {
        try {
            Object value = mapper.readValue(payload, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            return payload;
        }
    }

    private static ObjectMapper createDefault() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        if (isOnClasspath("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule")) {
            JavaTimeSupport.register(mapper);
        }

        return mapper;
    }

    private static boolean isOnClasspath(String fqn) {
        try {
            Class.forName(fqn, false, JacksonCodec.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static final class JavaTimeSupport {
        private JavaTimeSupport() {
        }

        static void register(ObjectMapper mapper) {
            mapper.registerModule(new JavaTimeModule());
        }
    }
}
