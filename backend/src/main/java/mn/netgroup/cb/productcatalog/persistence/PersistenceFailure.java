package mn.netgroup.cb.productcatalog.persistence;

/**
 * Any other failure reaching the transaction seam's boundary.
 *
 * <p>The seam translates jOOQ's own exception types here so that no layer above imports one.
 * Nothing reads the message: an unhandled failure becomes a 500 carrying a correlation
 * identifier and no other internal detail (FR-023).
 */
public final class PersistenceFailure extends RuntimeException {

    PersistenceFailure(Throwable cause) {
        super("a database operation failed", cause);
    }
}
