package com.moneytransfer.transfer.web.error;

import com.moneytransfer.transfer.web.CorrelationIdFilter;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

/**
 * The one error format every service uses:
 * <pre>{"error": {"code": "INSUFFICIENT_FUNDS", "message": "...", "requestId": "...", "details": []}}</pre>
 */
public record ApiError(Body error) {

    public record Body(String code, String message, String requestId, List<Map<String, Object>> details) { }

    public static ApiError of(String code, String message, List<Map<String, Object>> details) {
        return new ApiError(new Body(code, message, MDC.get(CorrelationIdFilter.MDC_KEY),
                details == null ? List.of() : details));
    }

    public static ApiError of(String code, String message) {
        return of(code, message, List.of());
    }
}
