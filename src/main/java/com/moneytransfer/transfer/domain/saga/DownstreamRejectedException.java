package com.moneytransfer.transfer.domain.saga;

/**
 * The downstream service answered with a definite "no" (a 4xx such as 409 INSUFFICIENT_FUNDS).
 * Never retried: repeating the call would get the same answer.
 */
public class DownstreamRejectedException extends RuntimeException {

    private final String service;
    private final int httpStatus;
    private final String code;

    public DownstreamRejectedException(String service, int httpStatus, String code, String message) {
        super(service + " rejected the request: " + code + " - " + message);
        this.service = service;
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public String service() { return service; }
    public int httpStatus() { return httpStatus; }
    public String code() { return code; }
    public String reason() {
        String m = getMessage();
        int i = m.indexOf(" - ");
        return i < 0 ? m : m.substring(i + 3);
    }
}
