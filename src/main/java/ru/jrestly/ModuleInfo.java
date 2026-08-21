package ru.jrestly;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.jrestly.util.Mapper;

public interface ModuleInfo {

    String getName();

    <T> Environment<T> getEnvironment();

    AuthProvider getAuthProvider();

    default ObjectMapper getObjectMapper() {
        return Mapper.defaults();
    }

    default int getConnectTimeout() {
        return 5_000;
    }

    default int getSocketTimeout() {
        return 30_000;
    }

    default int getConnectionRequestTimeout() {
        return 5_000;
    }

    default boolean isValidateControllers() {
        return true;
    }
}
