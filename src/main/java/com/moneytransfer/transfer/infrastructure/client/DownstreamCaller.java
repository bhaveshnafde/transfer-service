package com.moneytransfer.transfer.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneytransfer.transfer.domain.saga.DownstreamRejectedException;
import com.moneytransfer.transfer.domain.saga.DownstreamUnavailableException;
import com.moneytransfer.transfer.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Makes one HTTP call to another service and turns the outcome into the saga's vocabulary:
 * <ul>
 *   <li>2xx (or a status listed as "already done") is success</li>
 *   <li>other 4xx is {@link DownstreamRejectedException}: a definite no, never retried</li>
 *   <li>5xx, 408, 429, timeouts and connection errors are {@link DownstreamUnavailableException}:
 *       no answer, safe to retry</li>
 * </ul>
 * Every call forwards X-Request-ID (correlation) and an Idempotency-Key.
 */
public class DownstreamCaller {

    private final String serviceName;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public DownstreamCaller(String serviceName, String baseUrl, Duration connectTimeout, Duration readTimeout,
                            ObjectMapper objectMapper) {
        this.serviceName = serviceName;
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (requestId != null) {
                        request.getHeaders().set(CorrelationIdFilter.HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    public String serviceName() {
        return serviceName;
    }

    /** POST with a JSON body. Statuses in {@code alsoSuccess} (e.g. 404 on an undo) count as success. */
    public void post(String path, Object body, String idempotencyKey, Set<Integer> alsoSuccess) {
        call(HttpMethod.POST, path, body, idempotencyKey, alsoSuccess);
    }

    public void delete(String path, String idempotencyKey, Set<Integer> alsoSuccess) {
        call(HttpMethod.DELETE, path, null, idempotencyKey, alsoSuccess);
    }

    private void call(HttpMethod method, String path, Object body, String idempotencyKey, Set<Integer> alsoSuccess) {
        try {
            RestClient.RequestBodySpec spec = client.method(method)
                    .uri(path)
                    .header("Idempotency-Key", idempotencyKey);
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is2xxSuccessful() || alsoSuccess.contains(status)) {
                    return null;
                }
                String raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                ErrorInfo error = parseError(raw, status);
                if (status >= 500 || status == 408 || status == 429) {
                    throw new DownstreamUnavailableException(serviceName,
                            method + " " + path + " returned HTTP " + status + " " + error.code(), null);
                }
                throw new DownstreamRejectedException(serviceName, status, error.code(), error.message());
            });
        } catch (ResourceAccessException e) {
            // connection refused, connect timeout, read timeout
            throw new DownstreamUnavailableException(serviceName,
                    method + " " + path + " failed: " + rootMessage(e), e);
        }
    }

    private record ErrorInfo(String code, String message) { }

    /** Reads the team's shared error format {"error":{"code","message",...}}, falling back to the raw body. */
    private ErrorInfo parseError(String raw, int status) {
        try {
            JsonNode error = objectMapper.readTree(raw).path("error");
            if (error.hasNonNull("code")) {
                return new ErrorInfo(error.get("code").asText(), error.path("message").asText(""));
            }
        } catch (IOException | RuntimeException ignored) {
            // not JSON; fall through
        }
        String snippet = raw.length() > 200 ? raw.substring(0, 200) : raw;
        return new ErrorInfo("HTTP_" + status, snippet);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
