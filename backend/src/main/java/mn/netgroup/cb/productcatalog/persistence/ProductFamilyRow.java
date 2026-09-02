package mn.netgroup.cb.productcatalog.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code product_family}, in the persistence layer's own terms.
 *
 * <p>The persistence package depends on the generated jOOQ classes and on nothing else in this
 * repository (lld §2). This row type is why: the domain maps to and from it, so the dependency
 * runs one way — {@code domain/} → {@code persistence/} — and never back.
 *
 * <p>{@code status} is a {@code String} here deliberately: the column's admissible values are
 * bounded by {@code ck_product_family_status}, and turning them into a domain enum is the
 * domain's business, not this layer's.
 */
public record ProductFamilyRow(
        UUID id, String familyCode, String name, String status, Instant createdAt, Instant updatedAt) {}
