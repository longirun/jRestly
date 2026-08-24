package ru.jrestly.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
            throw new UncheckedIOException("Failed to serialize " + value.getClass().getName() + " to JSON", e);
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
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        JavaTimeModule module = new JavaTimeModule();
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));
        mapper.registerModule(module);

        return mapper;
    }
}
