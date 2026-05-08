package dev.victormartin.adbsidecar.back.util;

import java.util.List;
import java.util.Set;

// Single source of truth for OFAC-sanctioned country codes. Drives the
// R-OFAC-001 rule SQL (RiskController) and the cross-border wires
// `sanctioned` flag (FraudController). The SQL_LIST form is pre-quoted
// for inline use in `... IN (...)` predicates.
public final class OfacCountries {
    private OfacCountries() {}

    public static final List<String> COUNTRIES =
            List.of("BY", "IR", "KP", "RU", "SY", "VE", "MM", "CU");

    public static final Set<String> SET = Set.copyOf(COUNTRIES);

    public static final String SQL_LIST =
            "'" + String.join("','", COUNTRIES) + "'";
}
