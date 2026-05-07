package ru.jrestly.http;

import java.util.List;

public class OnErrorParser {

    private List<Integer> statuses;
    private Class<?> errorClass;

    public boolean canParse(int httpStatusCode) {
        return statuses.contains(httpStatusCode);
    }

    public List<Integer> getStatuses() {
        return statuses;
    }

    public void setStatuses(List<Integer> statuses) {
        this.statuses = statuses;
    }

    public Class<?> getErrorClass() {
        return errorClass;
    }

    public void setErrorClass(Class<?> errorClass) {
        this.errorClass = errorClass;
    }
}
