package ru.jrestly.http;

public enum RequestType {
    APPLICATION_JSON("application/json"),
    APPLICATION_FORM_URLENCODED("application/x-www-form-urlencoded"),
    MULTIPART_FORM_DATA("multipart/form-data");

    private final String mime;

    RequestType(String mime) {
        this.mime = mime;
    }

    public String mime() {
        return mime;
    }
}
