package dev.victormartin.aidatagateway.back.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudControllerTest {

    private JdbcTemplate adbJdbc;
    private JdbcTemplate oracleJdbc;
    private FraudController controller;

    @BeforeEach
    void setUp() {
        adbJdbc = mock(JdbcTemplate.class);
        oracleJdbc = mock(JdbcTemplate.class);
        when(adbJdbc.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of());
        when(oracleJdbc.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of());
        controller = new FraudController(adbJdbc, oracleJdbc);
    }

    @Test
    void default_window_anchors_to_newest_data_across_both_sources() {
        when(adbJdbc.queryForObject(contains("transaction_edges"), eq(LocalDateTime.class)))
                .thenReturn(LocalDateTime.parse("2026-04-16T14:29:00"));
        when(oracleJdbc.queryForObject(contains("transactions"), eq(LocalDateTime.class)))
                .thenReturn(LocalDateTime.parse("2026-04-02T10:00:00"));

        Map<String, Object> body = controller.patterns(null, null);

        assertThat(body.get("to")).isEqualTo("2026-04-16");
        assertThat(body.get("from")).isEqualTo("2026-03-17");
    }

    @Test
    void explicit_window_is_used_verbatim() {
        Map<String, Object> body = controller.patterns("2026-06-01", "2026-06-30");

        assertThat(body.get("from")).isEqualTo("2026-06-01");
        assertThat(body.get("to")).isEqualTo("2026-06-30");
    }
}
