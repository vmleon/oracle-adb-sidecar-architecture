package dev.victormartin.aidatagateway.back.measurement;

public record QueryMeasurement(
        String queryId,
        String route,
        double elapsedMs,
        int rowsReturned,
        boolean success,
        String errorClass,
        String runId) {
}
