package com.docqa.health;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Verifies that the pgvector extension is installed in PostgreSQL.
 * A missing or failed extension means vector search is unavailable.
 */
@Component("pgVector")
@RequiredArgsConstructor
public class PgVectorHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Health health() {
        try {
            String version = jdbcTemplate.queryForObject(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                    String.class);

            if (version == null) {
                return Health.down()
                        .withDetail("extension", "vector")
                        .withDetail("status", "not installed")
                        .build();
            }

            return Health.up()
                    .withDetail("extension", "vector")
                    .withDetail("version", version)
                    .build();

        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("extension", "vector")
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
