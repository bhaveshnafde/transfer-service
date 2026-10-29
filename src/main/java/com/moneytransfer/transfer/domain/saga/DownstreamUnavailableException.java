package com.moneytransfer.transfer.domain.saga;

/**
 * The downstream service could not give an answer: connection refused, timeout or 5xx.
 * The call may or may not have been applied, which is why every downstream call is idempotent
 * and safe to retry.
 */
public class DownstreamUnavailableException extends RuntimeException {

    private final String service;

    public DownstreamUnavailableException(String service, String message, Throwable cause) {
        super(service + " unavailable: " + message, cause);
        this.service = service;
    }

    public String service() { return service; }
}
