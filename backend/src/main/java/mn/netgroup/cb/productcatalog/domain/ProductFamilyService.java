package mn.netgroup.cb.productcatalog.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mn.netgroup.cb.productcatalog.api.error.CatalogFailure;
import mn.netgroup.cb.productcatalog.api.error.ErrorCode;
import mn.netgroup.cb.productcatalog.ids.FamilyIds;
import mn.netgroup.cb.productcatalog.persistence.ConstraintViolation;
import mn.netgroup.cb.productcatalog.persistence.ProductFamilyRepository;
import mn.netgroup.cb.productcatalog.persistence.ProductFamilyRow;
import mn.netgroup.cb.productcatalog.persistence.Tx;
import org.springframework.stereotype.Service;

/** The only caller of {@link ProductFamilyRepository}. */
@Service
public class ProductFamilyService {

    /** The unique index FR-018's duplicate detection is read off (lld §5 F-1 ⑤). */
    static final String FAMILY_CODE_INDEX = "ux_product_family_code";

    /** FR-017 — a name is 1 to 120 characters. */
    static final int NAME_MINIMUM = 1;

    static final int NAME_MAXIMUM = 120;

    private final Tx tx;
    private final ProductFamilyRepository repository;
    private final FamilyIds ids;
    private final Clock clock;

    public ProductFamilyService(Tx tx, ProductFamilyRepository repository, FamilyIds ids, Clock clock) {
        this.tx = tx;
        this.repository = repository;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * FR-001, FR-002, FR-003, FR-018.
     *
     * <p>The identifier comes from the one producer, both instants come from the injected clock,
     * and the status is {@code ACTIVE}. The duplicate family code is detected by the database's
     * own unique index — never by a pre-read, because a pre-read races — and the violation is
     * mapped by constraint name.
     */
    public ProductFamily create(String rawFamilyCode, String rawName) {
        FamilyCode familyCode = FamilyCode.of(rawFamilyCode);
        String name = validName(rawName);
        Instant now = now();

        ProductFamily family = new ProductFamily(
                ids.next(), familyCode, name, FamilyStatus.ACTIVE, now, now);

        try {
            tx.write(dsl -> {
                repository.insert(dsl, toRow(family));
                return null;
            });
        } catch (ConstraintViolation violation) {
            if (FAMILY_CODE_INDEX.equals(violation.constraintName())) {
                throw new CatalogFailure(ErrorCode.FAMILY_CODE_DUPLICATE);
            }
            throw violation;
        }
        return family;
    }

    /** FR-005, FR-019. */
    public ProductFamily findById(UUID id) {
        return tx.read(dsl -> repository.findById(dsl, id))
                .map(ProductFamilyService::toFamily)
                .orElseThrow(() -> new CatalogFailure(ErrorCode.FAMILY_NOT_FOUND));
    }

    /**
     * FR-006, FR-007, FR-008. One page, in the total order {@code (family_code, id)}.
     *
     * <p>{@code limit} rows are asked for exactly; deciding to ask for one more so a further page
     * can be detected is the pager's business, not this method's.
     */
    public List<ProductFamily> list(FamilyStatus status, FamilyCode afterFamilyCode, UUID afterId, int limit) {
        String statusFilter = status == null ? null : status.name();
        String seekCode = afterFamilyCode == null ? null : afterFamilyCode.value();
        return tx.read(dsl -> repository.findPage(dsl, statusFilter, seekCode, afterId, limit)).stream()
                .map(ProductFamilyService::toFamily)
                .toList();
    }

    /**
     * FR-010, FR-011, FR-019 — retire, idempotently.
     *
     * <p>Both statements sit in one {@link Tx#write} for connection and seam hygiene, <b>not</b>
     * because that removes an interleaving: at READ COMMITTED each statement takes a fresh
     * snapshot, so the re-read sees concurrently committed rows either way (lld D-10).
     *
     * <p>Zero affected rows is a <b>signal, never a no-op</b>, and the branch is taken on
     * {@code status}, not on presence. Inferring "found, so it must already be retired" from
     * presence alone would report a live family as retired if a row were inserted under the same
     * identifier between the two statements.
     *
     * <p>On the idempotent path the returned {@code updatedAt} is the <b>persisted</b> value,
     * never one stamped from the clock: FR-011 says the family comes back unchanged, and a fresh
     * timestamp would be a change.
     */
    public ProductFamily retire(UUID id) {
        return tx.write(dsl -> {
            Optional<ProductFamilyRow> transitioned = repository.retireIfActive(dsl, id, now());
            if (transitioned.isPresent()) {
                return toFamily(transitioned.orElseThrow());
            }

            ProductFamilyRow present = repository
                    .findById(dsl, id)
                    .orElseThrow(() -> new CatalogFailure(ErrorCode.FAMILY_NOT_FOUND));

            if (FamilyStatus.RETIRED.name().equals(present.status())) {
                return toFamily(present);
            }

            // Reachable only if a row was inserted under this identifier between the two
            // statements. Retry the guarded update once, immediately, inside the same
            // transaction; if it still affects no row the service stops rather than looping.
            return repository
                    .retireIfActive(dsl, id, now())
                    .map(ProductFamilyService::toFamily)
                    .orElseThrow(() -> new CatalogFailure(ErrorCode.INTERNAL_ERROR));
        });
    }

    /**
     * The one time source, truncated to the precision the store keeps.
     *
     * <p>{@code timestamptz} is microsecond-precision, so an untruncated {@code Instant} would
     * come back from the database differing from the one that was written — and FR-011 asks for a
     * body identical to the one the first retire returned, {@code updatedAt} included.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String validName(String raw) {
        if (raw == null || raw.length() < NAME_MINIMUM || raw.length() > NAME_MAXIMUM) {
            throw new CatalogFailure(ErrorCode.FAMILY_NAME_INVALID);
        }
        return raw;
    }

    private static ProductFamilyRow toRow(ProductFamily family) {
        return new ProductFamilyRow(
                family.id(),
                family.familyCode().value(),
                family.name(),
                family.status().name(),
                family.createdAt(),
                family.updatedAt());
    }

    private static ProductFamily toFamily(ProductFamilyRow row) {
        return new ProductFamily(
                row.id(),
                new FamilyCode(row.familyCode()),
                row.name(),
                FamilyStatus.valueOf(row.status()),
                row.createdAt(),
                row.updatedAt());
    }
}
