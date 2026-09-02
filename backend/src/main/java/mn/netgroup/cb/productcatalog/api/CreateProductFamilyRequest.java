package mn.netgroup.cb.productcatalog.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of a create-family request.
 *
 * <p>The status, the opaque identifier and both instants are the service's to assign and are not
 * accepted here (FR-002, FR-003). The declared bounds below are the contract's; the values are
 * <b>checked in the domain</b>, by {@code FamilyCode.of} and the service's name check, so that a
 * request reaching the service by any route is validated the same way.
 */
@Schema(
        name = "CreateProductFamilyRequest",
        description =
                "A request to register a product family. The status, the opaque identifier and both"
                        + " instants are the service's to assign and are not accepted here.")
public record CreateProductFamilyRequest(
        @Schema(minLength = 3, maxLength = 20, pattern = "^[A-Z0-9]{3,20}$") String familyCode,
        @Schema(minLength = 1, maxLength = 120) String name) {}
