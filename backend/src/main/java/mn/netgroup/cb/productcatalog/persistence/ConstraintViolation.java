package mn.netgroup.cb.productcatalog.persistence;

/**
 * An integrity constraint the database refused a write against, carrying the constraint's own
 * name.
 *
 * <p>The name is what callers branch on. FR-018's deduplication surface is the unique index
 * {@code ux_product_family_code}, and detection is the database's, never a pre-read, because a
 * pre-read races. Renaming that index in a later migration therefore degrades FR-018 to a 500,
 * silently — the integration test that inserts the same family code twice is the only thing
 * that catches it (lld §5 F-1 ⑤, plan §10).
 */
public final class ConstraintViolation extends RuntimeException {

    private final String constraintName;

    ConstraintViolation(String constraintName, Throwable cause) {
        super("a write was refused by constraint " + constraintName, cause);
        this.constraintName = constraintName;
    }

    public String constraintName() {
        return constraintName;
    }
}
