package mn.netgroup.cb.productcatalog.domain;

/**
 * The two states of a product family (spec §3).
 *
 * <p>{@code ACTIVE} is initial; {@code RETIRED} is terminal. There is no third value, and the
 * check constraint {@code ck_product_family_status} bounds the column to these two — so a status
 * this enum cannot name is a write the database refuses.
 *
 * <p>{@code RETIRED} is terminal <b>by the absence of surface</b>: no operation writes
 * {@code ACTIVE} after insert (FR-013).
 */
public enum FamilyStatus {
    ACTIVE,
    RETIRED
}
