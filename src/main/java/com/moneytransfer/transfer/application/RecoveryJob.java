package com.moneytransfer.transfer.application;

import com.moneytransfer.transfer.config.TransferProperties;
import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.TransferStatus;
import com.moneytransfer.transfer.domain.saga.TransferSagaOrchestrator;
import com.moneytransfer.transfer.infrastructure.persistence.TransferJpaRepository;
import com.moneytransfer.transfer.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/**
 * Finishes transfers that stopped in a non-terminal state: the service crashed mid-saga, an undo
 * failed (COMPENSATING), or the capture outcome was unknown (LEDGER_POSTED).
 *
 * Safe to run twice or alongside live requests: it only picks transfers untouched for
 * {@code stuckAfter}, every downstream call is idempotent, and the @Version on Transfer makes a
 * racing save fail instead of overwriting. Assumes one instance; with several, add
 * SELECT ... FOR UPDATE SKIP LOCKED.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "transfer.recovery", name = "enabled", havingValue = "true")
public class RecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(RecoveryJob.class);
    private static final EnumSet<TransferStatus> NON_TERMINAL = EnumSet.of(
            TransferStatus.PENDING, TransferStatus.LIMIT_RESERVED, TransferStatus.FUNDS_HELD,
            TransferStatus.LEDGER_POSTED, TransferStatus.COMPENSATING);

    private final TransferJpaRepository transfers;
    private final TransferSagaOrchestrator orchestrator;
    private final TransferApplicationService applicationService;
    private final TransferProperties properties;

    public RecoveryJob(TransferJpaRepository transfers, TransferSagaOrchestrator orchestrator,
                       TransferApplicationService applicationService, TransferProperties properties) {
        this.transfers = transfers;
        this.orchestrator = orchestrator;
        this.applicationService = applicationService;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${transfer.recovery.interval-ms}", fixedDelayString = "${transfer.recovery.interval-ms}")
    public void recoverStuckTransfers() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "recovery-" + Instant.now().toEpochMilli());
        try {
            Instant before = Instant.now().minus(properties.recovery().stuckAfter());
            List<Transfer> stuck = transfers.findStuck(NON_TERMINAL, before, PageRequest.of(0, 50));
            if (!stuck.isEmpty()) {
                log.info("Recovery found {} stuck transfer(s)", stuck.size());
            }
            for (Transfer t : stuck) {
                try {
                    Transfer resumed = orchestrator.resume(t);
                    applicationService.completeIdempotencyRecord(resumed);
                } catch (ObjectOptimisticLockingFailureException e) {
                    log.info("Transfer {} changed while recovering; leaving it for the next run", t.getId());
                } catch (RuntimeException e) {
                    log.error("Recovery of transfer {} failed", t.getId(), e);
                }
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
