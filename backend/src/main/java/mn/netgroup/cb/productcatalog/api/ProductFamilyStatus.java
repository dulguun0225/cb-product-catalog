package mn.netgroup.cb.productcatalog.api;

import io.swagger.v3.oas.annotations.media.Schema;
import mn.netgroup.cb.productcatalog.domain.FamilyStatus;

/**
 * The status as the contract declares it.
 *
 * <p>A wire type of its own, not the domain enum: the two happen to have the same members today,
 * and keeping them separate is what lets one change without silently changing the other. Binding
 * the {@code status} query parameter to this type is also what makes FR-022 fire — a value
 * outside these two cannot bind, and the advice turns that into
 * {@code 400 STATUS_FILTER_INVALID} rather than an empty result.
 */
@Schema(
        name = "ProductFamilyStatus",
        description = "The two states of a product family. ACTIVE is initial; RETIRED is terminal.")
public enum ProductFamilyStatus {
    ACTIVE,
    RETIRED;

    public FamilyStatus toDomain() {
        return FamilyStatus.valueOf(name());
    }

    public static ProductFamilyStatus of(FamilyStatus status) {
        return valueOf(status.name());
    }
}
