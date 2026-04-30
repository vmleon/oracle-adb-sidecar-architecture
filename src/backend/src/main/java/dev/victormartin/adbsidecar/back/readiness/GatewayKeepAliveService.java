package dev.victormartin.adbsidecar.back.readiness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

// Keep the heterogeneous-gateway sessions used by the AI agent task
// framework alive when no browser is connected.
//
// Background: DBMS_CLOUD_AI_AGENT.RUN_TEAM enumerates metadata across
// every configured DB_LINK during task warm-up. If a gateway session has
// gone idle (typical idle timeout: a few minutes), the next RUN_TEAM call
// fails on TASK_0 with ORA-01010 / ORA-02063 from PG_LINK — even when
// the active agent only reads Oracle Free views.
//
// The /api/v1/ready probe ReadinessService runs already touches both
// V_BNK_* views every 5 s, so gateways stay warm WHILE the frontend is
// open. This service closes the gap when no frontend is connected: the
// backend itself touches the same two views on a fixed cadence,
// independent of any user activity. The first returning user therefore
// hits a warm gateway instead of a 17 s retry-with-backoff stall.
@Service
public class GatewayKeepAliveService {

    private static final Logger log = LoggerFactory.getLogger(GatewayKeepAliveService.class);

    private final JdbcTemplate adbJdbc;

    public GatewayKeepAliveService(@Qualifier("adbJdbc") JdbcTemplate adbJdbc) {
        this.adbJdbc = adbJdbc;
    }

    // 90 s cadence is well below the typical 5+ minute heterogeneous
    // gateway idle timeout, with margin for occasional GC / scheduling
    // jitter. The initial 60 s delay lets AgentsService finish its
    // boot-time warm-up before we add traffic.
    @Scheduled(initialDelay = 60_000L, fixedDelay = 90_000L)
    public void keepLinksWarm() {
        try {
            adbJdbc.queryForObject("SELECT COUNT(*) FROM v_bnk_customers", Integer.class);
            adbJdbc.queryForObject("SELECT COUNT(*) FROM v_bnk_policies",  Integer.class);
            log.debug("DB_LINK keep-warm OK (orafree + pg)");
        } catch (Exception e) {
            // Don't propagate — Spring would log a stack trace and silence
            // the next scheduled run is unaffected. A WARN is enough for
            // operators; the next call will retry, and if a real outage
            // is in progress, /api/v1/ready will already be reporting it.
            log.warn("DB_LINK keep-warm probe failed: {}. Gateway likely cold; "
                    + "next agent call will rely on backend retry/backoff.",
                    e.getMessage());
        }
    }
}
