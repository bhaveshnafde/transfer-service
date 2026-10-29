package com.moneytransfer.transfer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneytransfer.transfer.application.NotificationPort;
import com.moneytransfer.transfer.domain.TransferRules;
import com.moneytransfer.transfer.domain.saga.AccountPort;
import com.moneytransfer.transfer.domain.saga.LedgerPort;
import com.moneytransfer.transfer.domain.saga.LimitsPort;
import com.moneytransfer.transfer.domain.saga.RetryPolicy;
import com.moneytransfer.transfer.domain.saga.StepLog;
import com.moneytransfer.transfer.domain.saga.TransferCompletedListener;
import com.moneytransfer.transfer.domain.saga.TransferSagaOrchestrator;
import com.moneytransfer.transfer.domain.saga.TransferStore;
import com.moneytransfer.transfer.infrastructure.client.AccountHttpClient;
import com.moneytransfer.transfer.infrastructure.client.DownstreamCaller;
import com.moneytransfer.transfer.infrastructure.client.LedgerHttpClient;
import com.moneytransfer.transfer.infrastructure.client.LimitsHttpClient;
import com.moneytransfer.transfer.infrastructure.client.NotificationHttpClient;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

/** Wires the plain-Java saga core to Spring and to the HTTP clients. */
@Configuration(proxyBeanMethods = false)
public class SagaConfig {

    @Bean
    TransferRules transferRules(TransferProperties props) {
        return new TransferRules(props.maxAmount());
    }

    @Bean
    RetryPolicy retryPolicy(TransferProperties props) {
        TransferProperties.Retry r = props.retry();
        return RetryPolicy.of(r.maxAttempts(), r.baseDelay(), r.maxDelay());
    }

    @Bean
    TransferSagaOrchestrator transferSagaOrchestrator(LimitsPort limits, AccountPort accounts, LedgerPort ledger,
                                                      TransferCompletedListener completedListener,
                                                      TransferStore store, StepLog stepLog, RetryPolicy retry) {
        return new TransferSagaOrchestrator(limits, accounts, ledger, completedListener, store, stepLog, retry);
    }

    @Bean
    LimitsPort limitsPort(TransferProperties props, ObjectMapper mapper) {
        return new LimitsHttpClient(caller("limits", props.downstream().limitsUrl(), props, mapper));
    }

    @Bean
    AccountPort accountPort(TransferProperties props, ObjectMapper mapper) {
        return new AccountHttpClient(caller("account", props.downstream().accountUrl(), props, mapper));
    }

    @Bean
    LedgerPort ledgerPort(TransferProperties props, ObjectMapper mapper) {
        return new LedgerHttpClient(caller("ledger", props.downstream().ledgerUrl(), props, mapper));
    }

    @Bean
    NotificationPort notificationPort(TransferProperties props, ObjectMapper mapper) {
        return new NotificationHttpClient(caller("notification", props.downstream().notificationUrl(), props, mapper));
    }

    /** Small pool for async notifications; copies requestId/transferId into the worker thread's logs. */
    @Bean(name = "notificationExecutor")
    TaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notify-");
        executor.setTaskDecorator(task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }

    private static DownstreamCaller caller(String name, String url, TransferProperties props, ObjectMapper mapper) {
        return new DownstreamCaller(name, url, props.downstream().connectTimeout(),
                props.downstream().readTimeout(), mapper);
    }
}
