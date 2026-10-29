package com.moneytransfer.transfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;

/**
 * Everything configurable, bound from application.yml, which reads environment variables.
 * The app refuses to start if a value is missing or invalid.
 */
@Validated
@ConfigurationProperties(prefix = "transfer")
public record TransferProperties(
        @NotNull @Positive BigDecimal maxAmount,
        @Valid @NotNull Downstream downstream,
        @Valid @NotNull Retry retry,
        @Valid @NotNull Recovery recovery) {

    public record Downstream(
            @NotBlank String limitsUrl,
            @NotBlank String accountUrl,
            @NotBlank String ledgerUrl,
            @NotBlank String notificationUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) { }

    public record Retry(
            @Min(1) int maxAttempts,
            @NotNull Duration baseDelay,
            @NotNull Duration maxDelay) { }

    public record Recovery(
            boolean enabled,
            @Min(1000) long intervalMs,
            @NotNull Duration stuckAfter) { }
}
