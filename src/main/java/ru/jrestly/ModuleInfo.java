package ru.jrestly;

import ru.jrestly.json.JsonCodec;
import ru.jrestly.json.JsonCodecResolver;

public interface ModuleInfo {

    String getName();

    <T> Environment<T> getEnvironment();

    AuthProvider getAuthProvider();

    default JsonCodec getJsonCodec() {
        return JsonCodecResolver.resolve();
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
