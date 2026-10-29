package com.moneytransfer.transfer.web.error;

import java.util.List;
import java.util.Map;

/** Thrown by application code to return a specific status and error code. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<Map<String, Object>> details;

    public ApiException(int status, String code, String message, List<Map<String, Object>> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : details;
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public int status() { return status; }
    public String code() { return code; }
    public List<Map<String, Object>> details() { return details; }
}
