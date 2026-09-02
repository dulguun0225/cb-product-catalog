package mn.netgroup.cb.productcatalog.api.error;

import java.util.Map;

/**
 * The declared bounds of the {@code limit} parameter, and the one place its failure is built.
 *
 * <p>FR-020, and lld D-04: any value the declared bounds do not admit is rejected with
 * {@code 400 LIMIT_ABOVE_MAXIMUM} carrying a {@code violation} member saying which bound broke,
 * plus the {@code min} and {@code max} that bound it. Those extension members are RFC 9457's own
 * mechanism and are additive for existing clients. Without them a client library reading
 * {@code LIMIT_ABOVE_MAXIMUM} for {@code limit=0} would halve its request and retry forever.
 *
 * <p>It is <b>never clamped</b> and never silently defaulted — the framework's own binding does
 * both, which is why the controller checks the raw parameter values rather than trusting the
 * bound one.
 */
public final class LimitViolation {

    public static final int MINIMUM = 1;
    public static final int MAXIMUM = 100;

    /** Above the maximum. */
    public static final String ABOVE_MAX = "ABOVE_MAX";

    /** Below the minimum. */
    public static final String BELOW_MIN = "BELOW_MIN";

    /** Not a base-10 32-bit integer at all — absent-but-present, repeated, or unparseable. */
    public static final String NOT_AN_INTEGER = "NOT_AN_INTEGER";

    private LimitViolation() {}

    public static CatalogFailure failure(String violation) {
        return new CatalogFailure(
                ErrorCode.LIMIT_ABOVE_MAXIMUM,
                Map.of("violation", violation, "min", MINIMUM, "max", MAXIMUM));
    }
}
