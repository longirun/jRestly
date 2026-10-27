package ru.jrestly.http;

public final class MediaType {

    public static final String APPLICATION_JSON = "application/json";

    public static final String APPLICATION_XML = "application/xml";

    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

    public static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";

    public static final String MULTIPART_FORM_DATA = "multipart/form-data";

    public static final String TEXT_PLAIN = "text/plain";

    public static final String TEXT_HTML = "text/html";

    public static final String TEXT_XML = "text/xml";

    public static final String APPLICATION_YAML = "application/yaml";

    public static final String TEXT_YAML = "text/yaml";

    public static final String APPLICATION_PROTOBUF = "application/protobuf";

    public static final String APPLICATION_X_PROTOBUF = "application/x-protobuf";

    public static final String TEXT_EVENT_STREAM = "text/event-stream";

    public static final String APPLICATION_NDJSON = "application/x-ndjson";

    public static final String ALL = "*/*";

    private MediaType() {
    }
}
