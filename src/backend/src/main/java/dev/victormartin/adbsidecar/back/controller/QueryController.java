package dev.victormartin.adbsidecar.back.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.victormartin.adbsidecar.back.measurement.MeasurementRecorder;
import dev.victormartin.adbsidecar.back.measurement.QueryMeasurement;
import dev.victormartin.adbsidecar.back.query.QueryExecutor;
import dev.victormartin.adbsidecar.back.query.QueryResult;

@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private final QueryExecutor executor;
    private final MeasurementRecorder recorder;

    public QueryController(QueryExecutor executor, MeasurementRecorder recorder) {
        this.executor = executor;
        this.recorder = recorder;
    }

    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> query(
            @RequestParam String table,
            @RequestParam String route,
            @RequestParam String runId) {

        if ("support_tickets".equals(table) && "federated".equals(route)) {
            Map<String, Object> body = Map.of("error",
                    "Not available via sidecar — see docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md");
            return ResponseEntity.status(501).body(body);
        }

        QueryResult result = executor.run(table, route);

        recorder.record(new QueryMeasurement(
                QueryExecutor.queryIdFor(table),
                route,
                result.elapsedMs(),
                result.rowsReturned(),
                result.success(),
                result.errorClass(),
                runId));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", result.rows());
        body.put("rowsReturned", result.rowsReturned());
        body.put("elapsedMs", result.elapsedMs());
        if (!result.success()) {
            body.put("error", result.errorMessage());
        }
        return ResponseEntity.ok(body);
    }
}
