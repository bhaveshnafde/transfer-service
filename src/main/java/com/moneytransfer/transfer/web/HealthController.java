package com.moneytransfer.transfer.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness plus a database check. Used by docker-compose / Kubernetes probes in Assignment 2. */
@RestController
@Tag(name = "Health")
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    @Operation(summary = "Service and database health")
    public ResponseEntity<Map<String, Object>> health() {
        String db;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            db = "UP";
        } catch (RuntimeException e) {
            db = "DOWN";
        }
        boolean up = "UP".equals(db);
        return ResponseEntity.status(up ? 200 : 503).body(Map.of(
                "status", up ? "UP" : "DOWN",
                "service", "transfer-service",
                "checks", Map.of("database", db)));
    }
}
