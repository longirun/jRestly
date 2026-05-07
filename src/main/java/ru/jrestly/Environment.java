package ru.jrestly;

public interface Environment<T> {
    String getUrl();
    T getAuthDetails();
    void setAuthDetails(T authDetails);
}
