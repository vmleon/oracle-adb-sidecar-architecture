package dev.victormartin.adbsidecar.back.query;

import java.util.List;
import java.util.Map;

public record QueryResult(
        List<Map<String, Object>> rows,
        int rowsReturned,
        double elapsedMs,
        boolean success,
        String errorClass,
        String errorMessage) {

    public static QueryResult success(List<Map<String, Object>> rows, double elapsedMs) {
        return new QueryResult(rows, rows.size(), elapsedMs, true, null, null);
    }

    public static QueryResult failure(Exception e, double elapsedMs) {
        return new QueryResult(List.of(), 0, elapsedMs, false,
                e.getClass().getSimpleName(), rootMessage(e));
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String top = e.getMessage();
        String bottom = root.getMessage();
        if (bottom == null || bottom.equals(top)) return top;
        return top + " — caused by: " + bottom;
    }
}
