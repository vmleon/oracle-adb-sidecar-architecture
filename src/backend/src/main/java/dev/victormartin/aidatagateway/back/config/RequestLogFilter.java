package dev.victormartin.aidatagateway.back.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tags every HTTP request with a short id, published to the logging MDC as
 * {@code rid} and echoed back in the {@code X-Request-Id} response header.
 *
 * The log pattern prints {@code rid=...} on every line, so one request's whole
 * story — controller, Select AI, agents, tools and each datasource — can be
 * pulled out with a single grep:
 *
 * <pre>grep 'rid=a3f19' app.log</pre>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
    private static final AtomicLong SEQ = new AtomicLong();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String rid = Long.toString(SEQ.incrementAndGet(), 36);
        MDC.put("rid", rid);
        res.setHeader("X-Request-Id", rid);

        long t0 = System.nanoTime();
        int status = 500;
        try {
            chain.doFilter(req, res);
            status = res.getStatus();
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            String path = req.getRequestURI();
            // Health and readiness are polled every few seconds; logging them
            // at INFO would bury everything else.
            boolean noisy = path.startsWith("/actuator")
                    || path.equals("/api/v1/health")
                    || path.equals("/api/v1/ready");
            String line = "event=http method={} path={} query={} status={} elapsed_ms={}";
            if (noisy) {
                log.debug(line, req.getMethod(), path, req.getQueryString(), status, ms);
            } else {
                log.info(line, req.getMethod(), path, req.getQueryString(), status, ms);
            }
            MDC.remove("rid");
        }
    }
}
