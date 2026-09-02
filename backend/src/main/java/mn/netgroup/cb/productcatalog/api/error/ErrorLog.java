package mn.netgroup.cb.productcatalog.api.error;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The one typed logging facade, and the only class in this repository that emits a log event.
 *
 * <p>java-backend-observability, "One typed logging facade": raw logger APIs,
 * {@code System.out} / {@code System.err} and {@code printStackTrace} are banned everywhere
 * else, and {@code BanListTest} enforces that. This feature emits exactly one application log
 * event, so the facade is one type with one method; the full event and metric catalogs, the
 * fan-out context capture and the cardinality budget are dormant here and lld §7 records each.
 *
 * <p>The method takes a correlation identifier and a throwable — a whitelisted scalar and a
 * failure — and no domain type can reach it, which is what "domain types are unloggable by type"
 * buys with a signature this narrow.
 */
@Component
public final class ErrorLog {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorLog.class);

    /**
     * The one event: an unhandled failure, at ERROR, carrying the identifier the 500 body
     * returns to the caller. java-backend-observability, "The correlation id in an error
     * response resolves to a log event" — the id in the body is this event's id, and an id that
     * retrieves nothing would turn FR-023 into a dead end.
     */
    public void internalError(UUID correlationId, Throwable cause) {
        String previous = MDC.get(CorrelationIdFilter.LOGGING_KEY);
        MDC.put(CorrelationIdFilter.LOGGING_KEY, correlationId.toString());
        try {
            LOG.error("unhandled failure serving a request, correlation id {}", correlationId, cause);
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationIdFilter.LOGGING_KEY);
            } else {
                MDC.put(CorrelationIdFilter.LOGGING_KEY, previous);
            }
        }
    }
}
