package com.moneytransfer.transfer.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "Transfer Service API",
        version = "v1",
        description = "Orchestrates money transfers across the Limits, Account, Ledger and Notification services. "
                + "Spec served at /v3/api-docs (JSON) and /v3/api-docs.yaml; UI at /swagger-ui.html."))
public class OpenApiConfig {
}
