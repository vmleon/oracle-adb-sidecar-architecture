package dev.victormartin.adbsidecar.back.measurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class MeasurementRecorder {

    private static final Logger log = LoggerFactory.getLogger(MeasurementRecorder.class);

    private static final String INSERT_SQL = """
            INSERT INTO query_measurements
              (query_id, route, elapsed_ms, rows_returned, success, error_class, run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate adbJdbc;

    public MeasurementRecorder(@Qualifier("adbJdbc") JdbcTemplate adbJdbc) {
        this.adbJdbc = adbJdbc;
    }

    @Async("measurementExecutor")
    public void record(QueryMeasurement m) {
        try {
            adbJdbc.update(INSERT_SQL,
                    m.queryId(),
                    m.route(),
                    m.elapsedMs(),
                    m.rowsReturned(),
                    m.success() ? 1 : 0,
                    m.errorClass(),
                    m.runId());
        } catch (Exception e) {
            log.warn("measurement insert failed: {}", e.getMessage());
        }
    }
}
