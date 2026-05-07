package ru.jrestly;

import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

public interface AuthProvider {

    Pair<String, String> getAuthHeader();

    void setHeaders(List<Pair<String, String>> cookies);

    void setHeaderName(String name);

    boolean isAuthorized();
}
