package mn.netgroup.cb.productcatalog.persistence;

import static mn.netgroup.cb.productcatalog.generated.tables.ProductFamily.PRODUCT_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mn.netgroup.cb.productcatalog.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The repository's statements, against the real schema.
 *
 * <p>001:FR-001 — "WHEN an API client submits a create-family request whose body satisfies every
 * field constraint in §2, the service shall persist a new product family with status ACTIVE."
 *
 * <p>001:FR-005 — "WHEN an API client requests a product family by its opaque identifier, the
 * service shall respond with that family's opaque identifier, family code, name, status,
 * created-at and updated-at." Every one of those six is asserted on the way back out.
 *
 * <p>001:FR-006 — "WHEN an API client requests the family list with no status filter, the
 * service shall include families of every status in the result."
 *
 * <p>001:FR-007 — "WHEN an API client requests the family list with a status filter, the service
 * shall include only families whose status equals the filter value."
 *
 * <p>001:FR-008 — "WHEN an API client requests the family list, the service shall order the
 * result by family code ascending with the opaque identifier as the final tiebreak." The seeded
 * set below deliberately contains two families sharing a name and families whose identifiers are
 * <em>not</em> in family-code order, so a result that happened to be ordered by identifier would
 * fail. The relative order of two rows sharing a family code is not asserted: the contract makes
 * it an explicit non-promise, and family codes are unique anyway.
 *
 * <p>001:FR-010 — "WHILE a product family is ACTIVE, WHEN an API client submits a retire request
 * for that family, the service shall transition it to RETIRED."
 */
class ProductFamilyRepositoryIT extends PostgresBackedTest {

    @Autowired Tx tx;
    @Autowired ProductFamilyRepository repository;

    private static final Instant T0 = Instant.parse("2026-09-02T08:15:30.120Z");

    @BeforeEach
    void emptyTheTable() {
        tx.write(dsl -> dsl.deleteFrom(PRODUCT_FAMILY).execute());
    }

    @Test
    void aRowIsWrittenAndReadBackFieldForField() {
        ProductFamilyRow written = new ProductFamilyRow(
                UUID.fromString("0192f3a1-0000-7000-8000-00000000000a"),
                "DEPOSITS",
                "Deposit products",
                "ACTIVE",
                T0,
                T0);
        tx.write(dsl -> {
            repository.insert(dsl, written);
            return null;
        });

        Optional<ProductFamilyRow> read = tx.read(dsl -> repository.findById(dsl, written.id()));

        assertThat(read).contains(written);
    }

    @Test
    void anIdentifierNoRowHoldsReadsAsEmpty() {
        Optional<ProductFamilyRow> read = tx.read(dsl ->
                repository.findById(dsl, UUID.fromString("0192f3a1-0000-7000-8000-0000000000ff")));

        assertThat(read).isEmpty();
    }

    @Test
    void anUnfilteredPageCarriesFamiliesOfEveryStatus() {
        seedTheStandardSet();

        List<String> codes = codesOf(tx.read(dsl -> repository.findPage(dsl, null, null, null, 50)));

        assertThat(codes).containsExactly("ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO");
    }

    @Test
    void aFilteredPageCarriesOnlyFamiliesOfThatStatus() {
        seedTheStandardSet();

        List<ProductFamilyRow> active = tx.read(dsl -> repository.findPage(dsl, "ACTIVE", null, null, 50));
        List<ProductFamilyRow> retired = tx.read(dsl -> repository.findPage(dsl, "RETIRED", null, null, 50));

        assertThat(codesOf(active)).containsExactly("ALPHA", "CHARLIE", "ECHO");
        assertThat(active).allSatisfy(row -> assertThat(row.status()).isEqualTo("ACTIVE"));
        assertThat(codesOf(retired)).containsExactly("BRAVO", "DELTA");
        assertThat(retired).allSatisfy(row -> assertThat(row.status()).isEqualTo("RETIRED"));
    }

    @Test
    void thePageIsOrderedByFamilyCodeAscendingAndNotByIdentifier() {
        seedTheStandardSet();

        List<ProductFamilyRow> page = tx.read(dsl -> repository.findPage(dsl, null, null, null, 50));

        assertThat(codesOf(page)).isSorted();
        // The seeded identifiers descend as the family codes ascend, so a result ordered by
        // identifier would be the exact reverse of the one FR-008 requires.
        assertThat(page.stream().map(ProductFamilyRow::id).toList())
                .isNotEqualTo(page.stream().map(ProductFamilyRow::id).sorted().toList());
    }

    @Test
    void seekingAfterTheLastRowOfAPageContinuesWhereItStopped() {
        seedTheStandardSet();

        List<ProductFamilyRow> first = tx.read(dsl -> repository.findPage(dsl, null, null, null, 2));
        ProductFamilyRow last = first.get(first.size() - 1);
        List<ProductFamilyRow> second =
                tx.read(dsl -> repository.findPage(dsl, null, last.familyCode(), last.id(), 2));

        assertThat(codesOf(first)).containsExactly("ALPHA", "BRAVO");
        assertThat(codesOf(second)).containsExactly("CHARLIE", "DELTA");
    }

    @Test
    void retireIfActiveTransitionsAnActiveRowAndReturnsIt() {
        seedTheStandardSet();
        UUID alpha = idOfCode("ALPHA");
        Instant retiredAt = T0.plus(1, ChronoUnit.HOURS);

        Optional<ProductFamilyRow> affected = tx.write(dsl -> repository.retireIfActive(dsl, alpha, retiredAt));

        assertThat(affected).isPresent();
        assertThat(affected.orElseThrow().status()).isEqualTo("RETIRED");
        assertThat(affected.orElseThrow().updatedAt()).isEqualTo(retiredAt);
        assertThat(affected.orElseThrow().createdAt()).isEqualTo(T0);
        assertThat(tx.read(dsl -> repository.findById(dsl, alpha)).orElseThrow().status())
                .isEqualTo("RETIRED");
    }

    @Test
    void retireIfActiveAffectsNoRowWhenTheFamilyIsAlreadyRetired() {
        seedTheStandardSet();
        UUID bravo = idOfCode("BRAVO");
        Instant wouldBe = T0.plus(1, ChronoUnit.HOURS);

        Optional<ProductFamilyRow> affected = tx.write(dsl -> repository.retireIfActive(dsl, bravo, wouldBe));

        assertThat(affected).isEmpty();
        // FR-011's "unchanged" begins here: the guarded update wrote nothing at all.
        assertThat(tx.read(dsl -> repository.findById(dsl, bravo)).orElseThrow().updatedAt())
                .isEqualTo(T0);
    }

    @Test
    void retireIfActiveAffectsNoRowWhenNoFamilyHoldsTheIdentifier() {
        Optional<ProductFamilyRow> affected = tx.write(dsl -> repository.retireIfActive(
                dsl, UUID.fromString("0192f3a1-0000-7000-8000-0000000000fe"), T0));

        assertThat(affected).isEmpty();
    }

    @Test
    void aDuplicateFamilyCodeSurfacesAsItsConstraintName() {
        seedTheStandardSet();
        ProductFamilyRow clash = new ProductFamilyRow(
                UUID.fromString("0192f3a1-0000-7000-8000-0000000000fd"), "ALPHA", "clash", "ACTIVE", T0, T0);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> tx.write(dsl -> {
            repository.insert(dsl, clash);
            return null;
        }));

        assertThat(thrown).isInstanceOf(ConstraintViolation.class);
        assertThat(((ConstraintViolation) thrown).constraintName()).isEqualTo("ux_product_family_code");
    }

    /**
     * Five families whose identifiers descend as their family codes ascend, two of them sharing a
     * name, and two of them already retired.
     */
    private void seedTheStandardSet() {
        List<ProductFamilyRow> rows = List.of(
                row("0192f3a1-0000-7000-8000-000000000005", "ALPHA", "shared name", "ACTIVE"),
                row("0192f3a1-0000-7000-8000-000000000004", "BRAVO", "shared name", "RETIRED"),
                row("0192f3a1-0000-7000-8000-000000000003", "CHARLIE", "third", "ACTIVE"),
                row("0192f3a1-0000-7000-8000-000000000002", "DELTA", "fourth", "RETIRED"),
                row("0192f3a1-0000-7000-8000-000000000001", "ECHO", "fifth", "ACTIVE"));
        tx.write(dsl -> {
            rows.forEach(row -> repository.insert(dsl, row));
            return null;
        });
    }

    private static ProductFamilyRow row(String id, String code, String name, String status) {
        return new ProductFamilyRow(UUID.fromString(id), code, name, status, T0, T0);
    }

    private UUID idOfCode(String code) {
        return tx.read(dsl -> repository.findPage(dsl, null, null, null, 50)).stream()
                .filter(row -> row.familyCode().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private static List<String> codesOf(List<ProductFamilyRow> rows) {
        return rows.stream().map(ProductFamilyRow::familyCode).toList();
    }
}
