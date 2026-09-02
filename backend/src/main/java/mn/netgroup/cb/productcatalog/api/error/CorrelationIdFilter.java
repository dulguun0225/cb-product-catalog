package mn.netgroup.cb.productcatalog.api.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import mn.netgroup.cb.productcatalog.ids.FamilyIds;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mints the correlation identifier, first in the chain.
 *
 * <p>lld D-07. A {@code @RestControllerAdvice} is part of dispatcher exception resolution and
 * never sees a throwable raised in a servlet filter or on the {@code /error} dispatch, so minting
 * the identifier in the advice would leave exactly the uncoded, identifier-less 500 the
 * observability rule exists to prevent. It is minted here instead, at
 * {@link Ordered#HIGHEST_PRECEDENCE}.
 *
 * <p>An inbound header never supplies one: a client cannot choose the identifier that retrieves
 * this service's log events.
 *
 * <p>The identifier goes into the SLF4J mapped diagnostic context in a {@code try}/{@code finally}
 * — Elastic Common Schema serialises that context into the JSON event, which is what makes the
 * identifier retrievable — and onto the request as an attribute, which is where
 * {@link ProblemAdvice} and {@link ErrorPathController} read it. The attribute is what survives
 * onto the {@code /error} dispatch, where the logging context of the first dispatch has already
 * been unwound; {@link ErrorLog} re-establishes the context around its own emit, so the
 * guarantee does not depend on which dispatch the failure surfaced in. With virtual threads there is no
 * pool and so no cross-request bleed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {

    /** The request attribute the two error paths read. */
    public static final String ATTRIBUTE = "catalog.correlationId";

    /** The logging-context key. ECS renders it into the event. */
    static final String LOGGING_KEY = "correlation_id";

    private final FamilyIds ids;

    public CorrelationIdFilter(FamilyIds ids) {
        this.ids = ids;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        UUID correlationId = ids.next();
        request.setAttribute(ATTRIBUTE, correlationId);
        MDC.put(LOGGING_KEY, correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LOGGING_KEY);
        }
    }
}
