package com.moneytransfer.transfer.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Format checks live here (Bean Validation). Business rules (BR1, BR4) live in
 * {@link com.moneytransfer.transfer.domain.TransferRules}, so they are in one place and unit-tested.
 */
public record TransferRequest(
        @Schema(example = "ACC-1001")
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,64}", message = "must be 3-64 letters, digits, '-' or '_'")
        String sourceAccountId,

        @Schema(example = "ACC-2002")
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,64}", message = "must be 3-64 letters, digits, '-' or '_'")
        String destinationAccountId,

        @Schema(example = "1500.00")
        @NotNull
        BigDecimal amount,

        @Schema(example = "INR")
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO currency code")
        String currency) {
}
