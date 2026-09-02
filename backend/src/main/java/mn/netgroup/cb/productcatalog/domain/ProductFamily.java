package mn.netgroup.cb.productcatalog.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A named grouping under which product definitions are later registered.
 *
 * <p>The one entity this feature introduces (spec §6). It has no relationships here; product
 * definitions will later reference a family by its opaque identifier, never by its family code.
 *
 * @param id the opaque identifier the service assigned — machine identity, meaningless to read,
 *     and the target of every future foreign key
 * @param familyCode the human-facing handle, client-supplied, unique, immutable, stored exactly
 *     as supplied
 * @param name the family's display name, 1–120 characters
 * @param status {@code ACTIVE} or {@code RETIRED}
 * @param createdAt the instant of first persistence, from the injected clock
 * @param updatedAt the instant of last change, from the injected clock
 */
public record ProductFamily(
        UUID id,
        FamilyCode familyCode,
        String name,
        FamilyStatus status,
        Instant createdAt,
        Instant updatedAt) {}
