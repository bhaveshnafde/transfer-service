package com.moneytransfer.transfer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.moneytransfer.transfer.application.NotificationPort;
import com.moneytransfer.transfer.domain.saga.AccountPort;
import com.moneytransfer.transfer.domain.saga.DownstreamUnavailableException;
import com.moneytransfer.transfer.domain.saga.LedgerPort;
import com.moneytransfer.transfer.domain.saga.LimitsPort;
import com.moneytransfer.transfer.infrastructure.persistence.TransferJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The HTTP API end to end inside this service: real controller, real database (H2), real saga.
 * Only the other services are mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TransferApiIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired TransferJpaRepository transfers;

    @MockitoBean LimitsPort limits;
    @MockitoBean AccountPort accounts;
    @MockitoBean LedgerPort ledger;
    @MockitoBean NotificationPort notifications;

    private static final String BODY = """
            {"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002","amount":1500.00,"currency":"INR"}
            """;

    @Test
    void completedTransferReturns201_andARepeatReplaysTheSameResponseWithNoSecondEffect() {
        String key = newKey();

        ResponseEntity<String> first = postRaw(key, BODY);
        ResponseEntity<String> second = postRaw(key, BODY);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody()).contains("\"status\":\"COMPLETED\"").contains("\"amount\":1500.00");
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(first.getHeaders().getLocation()).isNotNull();

        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody()); // byte-for-byte

        verify(limits, times(1)).reserve(any());
        verify(accounts, times(1)).placeHold(any());
        verify(ledger, times(1)).postJournal(any());
        verify(accounts, times(1)).captureHold(any());
    }

    @Test
    void sameKeyWithADifferentBodyIsRejectedWith422() {
        String key = newKey();
        post(key, BODY);

        ResponseEntity<JsonNode> reused = post(key, BODY.replace("1500.00", "9999.00"));

        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(reused.getBody().at("/error/code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        verify(limits, times(1)).reserve(any());
    }

    @Test
    void tenIdenticalRequestsAtTheSameMomentCreateExactlyOneTransfer() throws Exception {
        String key = newKey();
        // Make the first request slow, so the others arrive while it is still running.
        doAnswer(inv -> { Thread.sleep(300); return null; }).when(limits).reserve(any());

        int parallel = 10;
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                return post(key, BODY);
            }));
        }
        startTogether.countDown();
        List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
        for (Future<ResponseEntity<JsonNode>> f : futures) {
            responses.add(f.get());
        }
        pool.shutdown();

        Set<Integer> statuses = responses.stream().map(r -> r.getStatusCode().value()).collect(Collectors.toSet());
        assertThat(statuses).isSubsetOf(201, 409);
        long originals = responses.stream()
                .filter(r -> "false".equals(r.getHeaders().getFirst("Idempotent-Replayed"))).count();
        assertThat(originals).isEqualTo(1);
        responses.stream().filter(r -> r.getStatusCode().value() == 409).forEach(r ->
                assertThat(r.getBody().at("/error/code").asText()).isEqualTo("REQUEST_IN_PROGRESS"));

        long rowsForKey = transfers.findAll().stream().filter(t -> key.equals(t.getIdempotencyKey())).count();
        assertThat(rowsForKey).isEqualTo(1);
        verify(limits, times(1)).reserve(any());
        verify(accounts, times(1)).captureHold(any());

        // Once finished, every repeat gets the stored 201.
        ResponseEntity<JsonNode> after = post(key, BODY);
        assertThat(after.getStatusCode().value()).isEqualTo(201);
        assertThat(after.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void ledgerDown_returns503_andTheSagaStepsShowTheCompensation() {
        doThrow(new DownstreamUnavailableException("ledger", "connection refused", null))
                .when(ledger).postJournal(any());

        ResponseEntity<JsonNode> response = post(newKey(), BODY);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("LEDGER_UNAVAILABLE");
        String transferId = response.getBody().at("/error/details/0/transferId").asText();
        assertThat(response.getBody().at("/error/details/0/status").asText()).isEqualTo("FAILED");

        verify(ledger).reverseJournal(any());
        verify(accounts).releaseHold(any());
        verify(limits).release(any());
        verify(accounts, never()).captureHold(any());

        JsonNode steps = http.getForObject("/api/v1/transfers/" + transferId + "/saga-steps", JsonNode.class);
        List<String> trail = new ArrayList<>();
        steps.forEach(s -> trail.add(s.get("action").asText() + " " + s.get("step").asText() + " " + s.get("outcome").asText()));
        assertThat(trail).containsExactly(
                "EXECUTE LIMIT_RESERVATION SUCCEEDED",
                "EXECUTE FUNDS_HOLD SUCCEEDED",
                "EXECUTE LEDGER_JOURNAL UNAVAILABLE",
                "COMPENSATE LEDGER_JOURNAL SUCCEEDED",
                "COMPENSATE FUNDS_HOLD SUCCEEDED",
                "COMPENSATE LIMIT_RESERVATION SUCCEEDED");
    }

    @Test
    void BR1_amountAboveCapIsRejectedBeforeAnyServiceIsCalled() {
        ResponseEntity<JsonNode> response = post(newKey(), BODY.replace("1500.00", "100000.01"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("BR1_AMOUNT_ABOVE_CAP");
        verify(limits, never()).reserve(any());
    }

    @Test
    void missingIdempotencyKeyIs400InTheSharedErrorFormat() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-ID", "test-req-123");

        ResponseEntity<JsonNode> response = http.postForEntity("/api/v1/transfers",
                new HttpEntity<>(BODY, headers), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
        assertThat(response.getBody().at("/error/requestId").asText()).isEqualTo("test-req-123");
        assertThat(response.getHeaders().getFirst("X-Request-ID")).isEqualTo("test-req-123");
    }

    @Test
    void invalidFieldsAre400WithOneDetailPerField() {
        ResponseEntity<JsonNode> response = post(newKey(),
                "{\"sourceAccountId\":\"x\",\"destinationAccountId\":\"ACC-2002\",\"currency\":\"RUPEES\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        response.getBody().at("/error/details").forEach(d -> fields.add(d.get("field").asText()));
        assertThat(fields).containsExactlyInAnyOrder("sourceAccountId", "amount", "currency");
    }

    @Test
    void unknownTransferIs404() {
        ResponseEntity<JsonNode> response = http.getForEntity("/api/v1/transfers/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("TRANSFER_NOT_FOUND");
    }

    @Test
    void healthIsUp() {
        ResponseEntity<JsonNode> response = http.getForEntity("/health", JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status").asText()).isEqualTo("UP");
    }

    private ResponseEntity<JsonNode> post(String key, String body) {
        return http.postForEntity("/api/v1/transfers", request(key, body), JsonNode.class);
    }

    private ResponseEntity<String> postRaw(String key, String body) {
        return http.postForEntity("/api/v1/transfers", request(key, body), String.class);
    }

    private static HttpEntity<String> request(String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return new HttpEntity<>(body, headers);
    }

    private static String newKey() {
        return "test-" + UUID.randomUUID();
    }
}
