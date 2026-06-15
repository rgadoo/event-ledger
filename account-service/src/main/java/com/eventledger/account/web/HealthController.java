package com.eventledger.account.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight health endpoint required by the spec at {@code GET /health}.
 * Reports service status plus a live database-connectivity check. The richer
 * Spring Boot Actuator health tree remains available at {@code /actuator/health}.
 */
@RestController
public class HealthController {

    private final DataSource dataSource;
    private final String serviceName;

    public HealthController(DataSource dataSource,
                            @Value("${spring.application.name}") String serviceName) {
        this.dataSource = dataSource;
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbUp = isDatabaseReachable();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("service", serviceName);
        body.put("timestamp", Instant.now().toString());
        body.put("checks", Map.of("database", dbUp ? "UP" : "DOWN"));
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean isDatabaseReachable() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (Exception e) {
            return false;
        }
    }
}
