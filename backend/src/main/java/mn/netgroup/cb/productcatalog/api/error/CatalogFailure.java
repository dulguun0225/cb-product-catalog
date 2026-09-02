package mn.netgroup.cb.productcatalog.api.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The typed throw site for every coded failure in this service.
 *
 * <p>java-backend-api, "Every error carries a code from one compile-checked catalog", asks for a
 * typed-params record at the throw site. This is it: a code drawn from {@link ErrorCode} and the
 * extension members that code declares, and nothing else. No layer builds a body from one except
 * {@link ProblemDocuments}.
 *
 * <p>It carries no message of its own. FR-023 keeps exception text off the wire, and a failure
 * that never holds text cannot leak it.
 */
public final class CatalogFailure extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> parameters;

    public CatalogFailure(ErrorCode code) {
        this(code, Map.of());
    }

    public CatalogFailure(ErrorCode code, Map<String, Object> parameters) {
        super(code.name(), null, false, false);
        this.code = code;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }
}
