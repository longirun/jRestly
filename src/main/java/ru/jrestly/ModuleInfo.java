package ru.jrestly;

public interface ModuleInfo {

    String getName();

    <T> Environment<T> getEnvironment();

    AuthProvider getAuthProvider();
}
