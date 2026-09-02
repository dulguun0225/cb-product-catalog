package mn.netgroup.cb.productcatalog.api.error;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * The one factory that builds an error body.
 *
 * <p>java-backend-api, "One advice builds every error body". The rule is scoped to this factory
 * rather than to the advice class because a failure thrown in a servlet filter never reaches
 * {@code @RestControllerAdvice}: {@link ProblemAdvice} and {@link ErrorPathController} are its
 * two callers, and they are the only two (lld D-07). {@code BanListTest} enforces that no other
 * class constructs a {@code ProblemDetail}.
 *
 * <p>Every body is RFC 9457 {@code application/problem+json} and carries a {@code code} drawn
 * from {@link ErrorCode} (FR-015). The extension members are exactly the ones the code declares —
 * a mismatch is a programming error and fails here rather than shipping a body the committed
 * contract does not describe.
 */
@Component
public final class ProblemDocuments {

    public ProblemDetail of(ErrorCode code, URI instance) {
        return of(code, instance, Map.of());
    }

    public ProblemDetail of(ErrorCode code, URI instance, Map<String, Object> parameters) {
        if (!parameters.keySet().equals(new java.util.LinkedHashSet<>(code.parameterNames()))) {
            throw new IllegalArgumentException(
                    "%s declares parameters %s but was given %s"
                            .formatted(code, code.parameterNames(), parameters.keySet()));
        }

        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setTitle(code.status().getReasonPhrase());
        problem.setDetail(code.detail());
        problem.setInstance(instance);
        problem.setProperty("code", code.name());
        // Iterate the declared order, not the caller's, so two identical failures render alike.
        code.parameterNames().forEach(name -> problem.setProperty(name, parameters.get(name)));
        return problem;
    }

    /**
     * FR-023's body: the correlation identifier and no other internal detail — no exception
     * message, no class name, no stack frame.
     */
    public ProblemDetail internalError(URI instance, UUID correlationId) {
        return of(ErrorCode.INTERNAL_ERROR, instance, Map.of("correlationId", correlationId.toString()));
    }
}
