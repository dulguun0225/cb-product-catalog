package mn.netgroup.cb.productcatalog.persistence;

import static mn.netgroup.cb.productcatalog.generated.tables.ProductFamily.PRODUCT_FAMILY;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Explicit jOOQ DSL statements over {@code product_family}, and nothing else.
 *
 * <p>Every method takes its {@code DSLContext} from {@link Tx}: this class opens no transaction
 * and holds no context of its own. No attached-record CRUD, no {@code fetchOne}, no
 * {@code fetchAny}, no plain-SQL {@code String} construct — {@code fetchOptional} covers the
 * legitimately-optional read and {@code fetch} covers the page (java-backend-rules, "Fetch with
 * {@code fetchSingle} or {@code fetchOptional}", "Plain-SQL {@code String} constructs are
 * banned").
 *
 * <p>No statement here targets {@code family_code} in an {@code UPDATE}: FR-012 says the service
 * exposes no operation that changes a persisted family code, and the absence of such a statement
 * is half of what makes that true. The ban-list test asserts the other half.
 */
@Component
public final class ProductFamilyRepository {

    /** The projection every read below returns, in one place so the two cannot drift. */
    private static final org.jooq.Field<?>[] COLUMNS = {
        PRODUCT_FAMILY.ID,
        PRODUCT_FAMILY.FAMILY_CODE,
        PRODUCT_FAMILY.NAME,
        PRODUCT_FAMILY.STATUS,
        PRODUCT_FAMILY.CREATED_AT,
        PRODUCT_FAMILY.UPDATED_AT
    };

    public void insert(DSLContext dsl, ProductFamilyRow row) {
        dsl.insertInto(PRODUCT_FAMILY)
                .set(PRODUCT_FAMILY.ID, row.id())
                .set(PRODUCT_FAMILY.FAMILY_CODE, row.familyCode())
                .set(PRODUCT_FAMILY.NAME, row.name())
                .set(PRODUCT_FAMILY.STATUS, row.status())
                .set(PRODUCT_FAMILY.CREATED_AT, row.createdAt().atOffset(ZoneOffset.UTC))
                .set(PRODUCT_FAMILY.UPDATED_AT, row.updatedAt().atOffset(ZoneOffset.UTC))
                .execute();
    }

    public Optional<ProductFamilyRow> findById(DSLContext dsl, UUID id) {
        return dsl.select(COLUMNS)
                .from(PRODUCT_FAMILY)
                .where(PRODUCT_FAMILY.ID.eq(id))
                .fetchOptional()
                .map(ProductFamilyRepository::toRow);
    }

    /**
     * One keyset page.
     *
     * <p>The total order is {@code (family_code, id)} ascending: {@code family_code} is the one
     * client-meaningful sort column this entity has, and the identifier is appended <b>solely to
     * break ties</b> (FR-008). primary-keys, "A time-ordered key is not an ordering", bans
     * {@code ORDER BY} on an id column and grants exactly one carve-out for a keyset pager's
     * final tiebreak; this method is the single named seam that carve-out is scoped to, and the
     * ban-list test asserts the scoping. The identifier is a leading sort nowhere, appears in no
     * declared sort vocabulary, and the relative order of two families sharing a family code is
     * an explicit non-promise of the contract.
     *
     * <p>{@code afterFamilyCode} and {@code afterId} are supplied together or not at all. When
     * they are absent this is the first page and no seek predicate is added; when {@code status}
     * is absent no status predicate is added, which is what makes an unfiltered list return
     * families of every status (FR-006).
     */
    public List<ProductFamilyRow> findPage(
            DSLContext dsl, String status, String afterFamilyCode, UUID afterId, int limit) {

        Condition where = DSL.noCondition();
        if (status != null) {
            where = where.and(PRODUCT_FAMILY.STATUS.eq(status));
        }
        if (afterFamilyCode != null && afterId != null) {
            where = where.and(
                    DSL.row(PRODUCT_FAMILY.FAMILY_CODE, PRODUCT_FAMILY.ID)
                            .gt(DSL.row(DSL.val(afterFamilyCode), DSL.val(afterId))));
        }

        return dsl.select(COLUMNS)
                .from(PRODUCT_FAMILY)
                .where(where)
                .orderBy(PRODUCT_FAMILY.FAMILY_CODE.asc(), PRODUCT_FAMILY.ID.asc())
                .limit(limit)
                .fetch()
                .map(ProductFamilyRepository::toRow);
    }

    /**
     * The guarded transition.
     *
     * <p>{@code UPDATE … SET status = 'RETIRED' … WHERE id = ? AND status = 'ACTIVE' RETURNING …}.
     * An empty result is a <b>signal, never a no-op</b>: the caller re-reads and branches on
     * {@code status}, not on presence (lld D-10).
     */
    public Optional<ProductFamilyRow> retireIfActive(DSLContext dsl, UUID id, Instant updatedAt) {
        return dsl.update(PRODUCT_FAMILY)
                .set(PRODUCT_FAMILY.STATUS, "RETIRED")
                .set(PRODUCT_FAMILY.UPDATED_AT, updatedAt.atOffset(ZoneOffset.UTC))
                .where(PRODUCT_FAMILY.ID.eq(id).and(PRODUCT_FAMILY.STATUS.eq("ACTIVE")))
                .returning(COLUMNS)
                .fetchOptional()
                .map(record -> new ProductFamilyRow(
                        record.get(PRODUCT_FAMILY.ID),
                        record.get(PRODUCT_FAMILY.FAMILY_CODE),
                        record.get(PRODUCT_FAMILY.NAME),
                        record.get(PRODUCT_FAMILY.STATUS),
                        record.get(PRODUCT_FAMILY.CREATED_AT).toInstant(),
                        record.get(PRODUCT_FAMILY.UPDATED_AT).toInstant()));
    }

    private static ProductFamilyRow toRow(org.jooq.Record record) {
        return new ProductFamilyRow(
                record.get(PRODUCT_FAMILY.ID),
                record.get(PRODUCT_FAMILY.FAMILY_CODE),
                record.get(PRODUCT_FAMILY.NAME),
                record.get(PRODUCT_FAMILY.STATUS),
                record.get(PRODUCT_FAMILY.CREATED_AT).toInstant(),
                record.get(PRODUCT_FAMILY.UPDATED_AT).toInstant());
    }
}
