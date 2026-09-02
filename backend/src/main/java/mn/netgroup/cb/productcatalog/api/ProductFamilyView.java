package mn.netgroup.cb.productcatalog.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import mn.netgroup.cb.productcatalog.domain.ProductFamily;

/**
 * A product family as the contract declares it.
 *
 * <p>Instants are {@code java.time.Instant}, serialised as RFC 3339 date-time in UTC with a
 * {@code Z} designator, and both field names end {@code At} (java-backend-api, "Instants on the
 * wire"). A numeric or epoch timestamp is unwritable through this type.
 *
 * <p>The identifier crosses the wire as a string whatever the key type is (primary-keys, "Ids
 * cross the wire as strings").
 */
@Schema(
        name = "ProductFamily",
        description = "A named grouping under which product definitions are later registered.")
public record ProductFamilyView(
        @Schema(
                        format = "uuid",
                        description =
                                "The opaque identifier the service assigned. Clients never supply or"
                                        + " choose one, and nothing may be parsed out of it - it is not"
                                        + " an ordering.")
                UUID id,
        @Schema(
                        minLength = 3,
                        maxLength = 20,
                        pattern = "^[A-Z0-9]{3,20}$",
                        description =
                                "The human-facing handle, supplied at creation, unique across the"
                                        + " service, immutable, and stored exactly as supplied. Nothing"
                                        + " may parse meaning out of it.")
                String familyCode,
        @Schema(minLength = 1, maxLength = 120) String name,
        ProductFamilyStatus status,
        @Schema(description = "The instant the service first persisted the family, in UTC with a Z designator.")
                Instant createdAt,
        @Schema(description = "The instant the service last changed the family, in UTC with a Z designator.")
                Instant updatedAt) {

    public static ProductFamilyView of(ProductFamily family) {
        return new ProductFamilyView(
                family.id(),
                family.familyCode().value(),
                family.name(),
                ProductFamilyStatus.of(family.status()),
                family.createdAt(),
                family.updatedAt());
    }
}
