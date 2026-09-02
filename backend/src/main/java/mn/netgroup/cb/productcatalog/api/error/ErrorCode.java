package mn.netgroup.cb.productcatalog.api.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * The one compile-checked catalog of machine-readable error codes.
 *
 * <p>java-backend-api, "Every error carries a code from one compile-checked catalog": clients
 * integrate against {@code code} and never against {@code title} or {@code detail} prose
 * (SC-003). The catalog is closed — the committed contract declares exactly these eight members,
 * and {@code ErrorCatalogSnapshotTest} diffs every {@code (code, status, param-names)} triple, so
 * adding, removing or re-typing one is a git-visible re-approval rather than a silent change.
 *
 * <p>This enum has no dependency of its own. That is deliberate: it is the leaf the domain may
 * cite at a throw site without any layer above depending on HTTP.
 *
 * <p><b>D-08, and it is a defect in the approved artifacts rather than a design choice.</b> The
 * protocol-level failures the framework produces before an operation is entered — 400 on an
 * unreadable JSON body, 405, 406, 415 — are RFC 9457 documents with the right status but have no
 * member here, so they carry no {@code code} and FR-015 is not fully true. Resolving it edits an
 * approved document, which this stage may not do; the question is the design's OI-006.
 */
public enum ErrorCode {

    /** FR-016. */
    FAMILY_CODE_INVALID(
            HttpStatus.BAD_REQUEST, "The family code does not satisfy its declared form.", List.of()),

    /** FR-017. */
    FAMILY_NAME_INVALID(
            HttpStatus.BAD_REQUEST, "The name does not satisfy its declared length.", List.of()),

    /** FR-018. */
    FAMILY_CODE_DUPLICATE(
            HttpStatus.CONFLICT, "That family code is already held by a persisted family.", List.of()),

    /** FR-019. */
    FAMILY_NOT_FOUND(HttpStatus.NOT_FOUND, "No product family holds that identifier.", List.of()),

    /** FR-020. The three extension members are RFC 9457's own mechanism (D-04). */
    LIMIT_ABOVE_MAXIMUM(
            HttpStatus.BAD_REQUEST,
            "limit must be an integer between 1 and 100.",
            List.of("violation", "min", "max")),

    /** FR-021. */
    CURSOR_INVALID(
            HttpStatus.BAD_REQUEST,
            "The cursor failed its integrity check or was issued for a different sort specification.",
            List.of()),

    /** FR-022. */
    STATUS_FILTER_INVALID(
            HttpStatus.BAD_REQUEST, "The status filter is neither ACTIVE nor RETIRED.", List.of()),

    /** FR-023. The correlation identifier is the only internal detail a 500 exposes. */
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "The request could not be completed.",
            List.of("correlationId"));

    private final HttpStatus status;
    private final String detail;
    private final List<String> parameterNames;

    ErrorCode(HttpStatus status, String detail, List<String> parameterNames) {
        this.status = status;
        this.detail = detail;
        this.parameterNames = List.copyOf(parameterNames);
    }

    public HttpStatus status() {
        return status;
    }

    /** Human-readable prose. Not stable, and nothing branches on it. */
    public String detail() {
        return detail;
    }

    /** The extension members a document carrying this code declares, and no others. */
    public List<String> parameterNames() {
        return parameterNames;
    }
}
