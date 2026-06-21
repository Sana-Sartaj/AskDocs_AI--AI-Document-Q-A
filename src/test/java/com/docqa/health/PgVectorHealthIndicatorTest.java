package com.docqa.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PgVectorHealthIndicatorTest {

    @Mock JdbcTemplate jdbcTemplate;

    @InjectMocks PgVectorHealthIndicator indicator;

    @Test
    void health_extensionInstalled_returnsUp() {
        given(jdbcTemplate.queryForObject(anyString(), any(Class.class))).willReturn("0.7.0");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("version", "0.7.0");
    }

    @Test
    void health_extensionNotInstalled_returnsDown() {
        given(jdbcTemplate.queryForObject(anyString(), any(Class.class))).willReturn(null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("status", "not installed");
    }

    @Test
    void health_queryThrowsException_returnsDown() {
        given(jdbcTemplate.queryForObject(anyString(), any(Class.class)))
                .willThrow(new RuntimeException("connection refused"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
