package mn.netgroup.cb.productcatalog.api.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import mn.netgroup.cb.productcatalog.ids.FamilyIds;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one advice. Every error body a handler path produces is built through
 * {@link ProblemDocuments} from here.
 *
 * <p>java-backend-api, "One advice builds every error body": an unknown throwable becomes a
 * generic coded internal problem carrying <b>only</b> a correlation identifier, and the
 * exception's message, class name and stack never reach the wire (FR-023). The identifier is read
 * off the request — {@link CorrelationIdFilter} minted it — and never minted here.
 *
 * <p><b>D-04.</b> The framework's own 400 for a bad {@code limit} or {@code status} carries no
 * {@code code} member, which would breach FR-015 through the happy path. Both framework
 * exceptions are handled below and are <b>discriminated by parameter name</b>, because the same
 * exception class carries the {@code limit} and {@code status} failures.
 */
@RestControllerAdvice
public class ProblemAdvice extends ResponseEntityExceptionHandler {

    private final ProblemDocuments problems;
    private final ErrorLog errorLog;
    private final FamilyIds ids;

    public ProblemAdvice(ProblemDocuments problems, ErrorLog errorLog, FamilyIds ids) {
        this.problems = problems;
        this.errorLog = errorLog;
        this.ids = ids;
    }

    /** Every failure this service raises deliberately. */
    @ExceptionHandler(CatalogFailure.class)
    public ResponseEntity<ProblemDetail> onCatalogFailure(CatalogFailure failure, HttpServletRequest request) {
        ProblemDetail body = problems.of(failure.code(), instanceOf(request), failure.parameters());
        return ResponseEntity.status(failure.code().status()).body(body);
    }

    /**
     * A {@code limit} or {@code status} the framework could not bind — {@code limit=abc},
     * {@code limit=} , a value beyond a 32-bit integer, {@code status=DRAFT}. Discriminated by
     * the parameter's own name (D-04).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> onUnbindableParameter(
            MethodArgumentTypeMismatchException mismatch, HttpServletRequest request) {
        return forParameter(mismatch.getName(), LimitViolation.NOT_AN_INTEGER)
                .map(failure -> onCatalogFailure(failure, request))
                .orElseGet(() -> onAnythingElse(mismatch, request));
    }

    /**
     * A {@code limit} that bound but broke its declared bounds — {@code limit=101},
     * {@code limit=0}.
     *
     * <p>An override rather than a second {@code @ExceptionHandler}: this exception is already
     * mapped by {@code ResponseEntityExceptionHandler}, and adding an equally specific handler
     * beside it makes the mapping ambiguous and fails at startup. The framework's own body for it
     * carries no {@code code}, which is what D-04 says would breach FR-015 through the happy path.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException invalid,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        HttpServletRequest servletRequest = servletRequestOf(request);
        String parameter = invalid.getAllValidationResults().stream()
                .map(result -> result.getMethodParameter().getParameterName())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("");
        ResponseEntity<ProblemDetail> answer = forParameter(parameter, violationOf(invalid))
                .map(failure -> onCatalogFailure(failure, servletRequest))
                .orElseGet(() -> onAnythingElse(invalid, servletRequest));
        return ResponseEntity.status(answer.getStatusCode()).body(answer.getBody());
    }

    /**
     * Which bound the value broke, so a client library can repair the request rather than retry
     * it unchanged (D-04). Without it, a client reading {@code LIMIT_ABOVE_MAXIMUM} for
     * {@code limit=0} would halve and retry forever.
     */
    private static String violationOf(HandlerMethodValidationException invalid) {
        boolean belowMinimum = invalid.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .anyMatch(error -> String.valueOf(error.getCodes() == null ? "" : java.util.Arrays.toString(error.getCodes()))
                        .contains("Min"));
        return belowMinimum ? LimitViolation.BELOW_MIN : LimitViolation.ABOVE_MAX;
    }

    private static HttpServletRequest servletRequestOf(WebRequest request) {
        return ((NativeWebRequest) request).getNativeRequest(HttpServletRequest.class);
    }

    /** FR-023. The unknown-throwable branch. */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> onAnythingElse(Throwable failure, HttpServletRequest request) {
        UUID correlationId = correlationIdOf(request);
        errorLog.internalError(correlationId, failure);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(problems.internalError(instanceOf(request), correlationId));
    }

    /**
     * The two query parameters this contract codes for, by name.
     *
     * <p>Empty for anything else. A parameter the framework could not bind that is neither of
     * these is not a coded client error in this contract, and becomes the generic internal
     * problem rather than a code borrowed from a neighbouring parameter.
     *
     * <p>The framework's own protocol-level failures — an unreadable JSON body, 405, 406, 415 —
     * are handled by {@code ResponseEntityExceptionHandler} and keep the RFC 9457 shape and status
     * it gives them, carrying no {@code code}. The approved eight-member catalog has no member for
     * them, and adding one edits an approved document this stage may not edit. That is D-08, and
     * the design's OI-006 asks the requester to resolve it.
     */
    private static java.util.Optional<CatalogFailure> forParameter(String parameterName, String violation) {
        if ("status".equals(parameterName)) {
            return java.util.Optional.of(new CatalogFailure(ErrorCode.STATUS_FILTER_INVALID));
        }
        if ("limit".equals(parameterName)) {
            return java.util.Optional.of(LimitViolation.failure(violation));
        }
        return java.util.Optional.empty();
    }

    private static URI instanceOf(HttpServletRequest request) {
        return URI.create(request.getRequestURI());
    }

    private UUID correlationIdOf(HttpServletRequest request) {
        Object minted = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        if (minted instanceof UUID correlationId) {
            return correlationId;
        }
        // Unreachable while the filter is registered at HIGHEST_PRECEDENCE. A 500 carrying an
        // identifier that retrieves nothing would be worse than one carrying a fresh identifier
        // the log event does then carry, so this branch takes one from the one producer — never
        // from a version-4 generator, which is banned by name outside FamilyIds.
        return ids.next();
    }
}
