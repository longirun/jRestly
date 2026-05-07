package ru.jrestly.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import ru.jrestly.AppProperties;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Mapper {
    private ObjectMapper mapper;

    private static Mapper instance;

    public static Mapper instance() {
        if (instance == null) {
            instance = new Mapper();
        }
        return instance;
    }

    public Mapper() {
        mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);

        boolean failOnUnknown = false;
        if (AppProperties.all() != null) {
            failOnUnknown = AppProperties.all().getBoolean("api.mapper.fail-on-unknown-properties", false);
        }
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failOnUnknown);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String dateFormat = AppProperties.get("api.mapper.date-time-format");
        if (dateFormat == null) {
            dateFormat = "yyyy-MM-dd HH:mm:ss";
        }
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(dateFormat);

        JavaTimeModule module = new JavaTimeModule();
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));
        mapper.registerModule(module);
    }

    public static ObjectMapper getMapper() {
        return instance().mapper;
    }
}
