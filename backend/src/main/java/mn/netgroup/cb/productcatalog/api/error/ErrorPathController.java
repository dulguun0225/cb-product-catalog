package mn.netgroup.cb.productcatalog.api.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import mn.netgroup.cb.productcatalog.ids.FamilyIds;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code /error} dispatch, and {@link ProblemDocuments}'s second caller.
 *
 * <p>lld D-07. A failure thrown in a servlet filter never reaches
 * {@code @RestControllerAdvice} — dispatcher exception resolution has not begun — so without
 * this controller such a failure would leave the framework's own uncoded, identifier-less body.
 * That is exactly the response FR-023 exists to prevent, and it is why "one place an error body
 * is built" is scoped to the factory rather than to the advice class.
 *
 * <p>The correlation identifier is read off the request, where {@link CorrelationIdFilter} put
 * it on the first dispatch. It is never minted here.
 */
@RestController
public final class ErrorPathController implements ErrorController {

    private final ProblemDocuments problems;
    private final ErrorLog errorLog;
    private final FamilyIds ids;

    public ErrorPathController(ProblemDocuments problems, ErrorLog errorLog, FamilyIds ids) {
        this.problems = problems;
        this.errorLog = errorLog;
        this.ids = ids;
    }

    @RequestMapping(value = "${server.error.path:/error}", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public ResponseEntity<ProblemDetail> onErrorDispatch(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        URI instance = URI.create(originalPathOf(request));

        if (status.is5xxServerError()) {
            UUID correlationId = correlationIdOf(request);
            errorLog.internalError(correlationId, thrownOn(request));
            return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                    .body(problems.internalError(instance, correlationId));
        }

        // A 4xx that reached the error dispatch without passing through the advice — a request
        // for a path no handler serves, most of all. FR-019 answers an address no family holds
        // with FAMILY_NOT_FOUND, and this is the same answer for the same reason.
        if (status == HttpStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(problems.of(ErrorCode.FAMILY_NOT_FOUND, instance));
        }

        // Everything else keeps the status the framework decided and the problem shape, with no
        // code member: the approved catalog has no member for a protocol-level failure (D-08,
        // OI-006).
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setTitle(status.getReasonPhrase());
        body.setInstance(instance);
        return ResponseEntity.status(status).body(body);
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer statusCode) {
            HttpStatus resolved = HttpStatus.resolve(statusCode);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String originalPathOf(HttpServletRequest request) {
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return original instanceof String path ? path : request.getRequestURI();
    }

    private static Throwable thrownOn(HttpServletRequest request) {
        Object thrown = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        return thrown instanceof Throwable failure ? failure : null;
    }

    private UUID correlationIdOf(HttpServletRequest request) {
        Object minted = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        // Unreachable while the filter is registered at HIGHEST_PRECEDENCE, and never null on
        // the wire: an identifier that retrieves nothing would make FR-023 a dead end, so this
        // branch takes one from the one producer and the log event below carries it.
        return minted instanceof UUID correlationId ? correlationId : ids.next();
    }
}
