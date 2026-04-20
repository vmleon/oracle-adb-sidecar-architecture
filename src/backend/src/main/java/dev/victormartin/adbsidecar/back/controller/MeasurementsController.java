package dev.victormartin.adbsidecar.back.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/measurements")
public class MeasurementsController {

    private static final String AGG_RAW = """
            SELECT
              query_id      AS "queryId",
              route         AS "route",
              COUNT(*)      AS "count",
              AVG(elapsed_ms)   AS "mean",
              MEDIAN(elapsed_ms) AS "median",
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY elapsed_ms) AS "p95",
              PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY elapsed_ms) AS "p99",
              STDDEV(elapsed_ms) AS "stddev",
              MIN(elapsed_ms)    AS "min",
              MAX(elapsed_ms)    AS "max"
            FROM query_measurements
            WHERE success = 1
            GROUP BY query_id, route
            ORDER BY query_id, route
            """;

    private static final String AGG_IQR = """
            WITH q AS (
              SELECT query_id, route, elapsed_ms,
                     PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY elapsed_ms)
                       OVER (PARTITION BY query_id, route) AS q1,
                     PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY elapsed_ms)
                       OVER (PARTITION BY query_id, route) AS q3
              FROM query_measurements
              WHERE success = 1
            ),
            trimmed AS (
              SELECT query_id, route, elapsed_ms
              FROM q
              WHERE elapsed_ms BETWEEN q1 - 1.5 * (q3 - q1) AND q3 + 1.5 * (q3 - q1)
            )
            SELECT
              query_id      AS "queryId",
              route         AS "route",
              COUNT(*)      AS "count",
              AVG(elapsed_ms)    AS "mean",
              MEDIAN(elapsed_ms) AS "median",
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY elapsed_ms) AS "p95",
              PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY elapsed_ms) AS "p99",
              STDDEV(elapsed_ms) AS "stddev",
              MIN(elapsed_ms)    AS "min",
              MAX(elapsed_ms)    AS "max"
            FROM trimmed
            GROUP BY query_id, route
            ORDER BY query_id, route
            """;

    private static final String SERIES = """
            SELECT
              TO_CHAR(measured_at AT TIME ZONE 'UTC',
                      'YYYY-MM-DD"T"HH24:MI:SS.FF3"Z"') AS "measuredAt",
              elapsed_ms   AS "elapsedMs",
              rows_returned AS "rowsReturned",
              success      AS "success",
              run_id       AS "runId"
            FROM (
              SELECT *
              FROM query_measurements
              WHERE query_id = ? AND route = ?
              ORDER BY measured_at DESC
              FETCH FIRST ? ROWS ONLY
            )
            ORDER BY "measuredAt"
            """;

    private final JdbcTemplate adbJdbc;

    public MeasurementsController(@Qualifier("adbJdbc") JdbcTemplate adbJdbc) {
        this.adbJdbc = adbJdbc;
    }

    @GetMapping
    public List<Map<String, Object>> aggregate(
            @RequestParam(defaultValue = "none") String trim) {
        String sql = "iqr".equalsIgnoreCase(trim) ? AGG_IQR : AGG_RAW;
        return adbJdbc.queryForList(sql);
    }

    @GetMapping("/series")
    public List<Map<String, Object>> series(
            @RequestParam String queryId,
            @RequestParam String route,
            @RequestParam(defaultValue = "200") int limit) {
        int capped = Math.max(1, Math.min(limit, 1000));
        return adbJdbc.queryForList(SERIES, queryId, route, capped);
    }
}
