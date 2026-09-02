package mn.netgroup.cb.productcatalog.domain;

import static mn.netgroup.cb.productcatalog.generated.tables.ProductFamily.PRODUCT_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import mn.netgroup.cb.productcatalog.api.error.CatalogFailure;
import mn.netgroup.cb.productcatalog.api.error.ErrorCode;
import mn.netgroup.cb.productcatalog.persistence.Tx;
import mn.netgroup.cb.productcatalog.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The domain service's rules, against real PostgreSQL.
 *
 * <p>001:FR-001 — "WHEN an API client submits a create-family request whose body satisfies every
 * field constraint in §2, the service shall persist a new product family with status ACTIVE."
 *
 * <p>001:FR-002 — "WHEN the service persists a new product family, the service shall assign it an
 * opaque identifier that no client supplied." Nothing in {@code create}'s signature accepts one.
 *
 * <p>001:FR-003 — "WHEN the service persists or changes a product family, the service shall
 * record the created-at and updated-at instants from the injected clock." Asserted against a
 * clock this test supplies, so a wall-clock read anywhere in the path would produce an instant
 * this test does not recognise.
 *
 * <p>001:FR-010 — "WHILE a product family is ACTIVE, WHEN an API client submits a retire request
 * for that family, the service shall transition it to RETIRED."
 *
 * <p>001:FR-011 — "WHILE a product family is RETIRED, WHEN an API client submits a retire request
 * for that family, the service shall respond with that family unchanged." <em>Unchanged</em> is
 * the whole assertion: the same identifier, family code, name, status, created-at <b>and
 * updated-at</b>. The clock advances between the two calls, so a service that stamped a fresh
 * instant on the idempotent path would fail here.
 *
 * <p>001:FR-012 — "The service shall expose no operation that changes a persisted family code."
 *
 * <p>001:FR-013 — "The service shall expose no operation that transitions a product family out of
 * RETIRED."
 *
 * <p>001:FR-016 — "IF a create-family request carries a family code that is not 3 to 20
 * characters drawn from A–Z and 0–9, THEN the service shall reject the request with a 400 problem
 * document carrying error code FAMILY_CODE_INVALID."
 *
 * <p>001:FR-017 — "IF a create-family request carries a name that is not 1 to 120 characters,
 * THEN the service shall reject the request with a 400 problem document carrying error code
 * FAMILY_NAME_INVALID."
 *
 * <p>001:FR-018 — "IF a create-family request carries a family code a persisted family already
 * holds, THEN the service shall reject the request with a 409 problem document carrying error
 * code FAMILY_CODE_DUPLICATE."
 *
 * <p>001:FR-019 — "IF a request addresses a product family by an opaque identifier no persisted
 * family holds, THEN the service shall reject the request with a 404 problem document carrying
 * error code FAMILY_NOT_FOUND."
 */
@Import(ProductFamilyServiceIT.SteppedClockConfiguration.class)
class ProductFamilyServiceIT extends PostgresBackedTest {

    /** The instant the stepped clock starts at. Microsecond precision, as {@code timestamptz} is. */
    static final Instant ORIGIN = Instant.parse("2026-09-02T08:15:30.120000Z");

    /**
     * A clock that advances one millisecond per read.
     *
     * <p>Every instant it hands out is distinct, which is what turns "the same updatedAt" below
     * into a real assertion rather than a coincidence of a frozen clock.
     */
    static final class SteppedClock extends Clock {
        private final AtomicLong step = new AtomicLong();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ORIGIN.plus(step.getAndIncrement(), ChronoUnit.MILLIS);
        }
    }

    @TestConfiguration
    static class SteppedClockConfiguration {
        @Bean
        @Primary
        Clock steppedClock() {
            return new SteppedClock();
        }
    }

    @Autowired ProductFamilyService service;
    @Autowired Tx tx;

    @BeforeEach
    void emptyTheTable() {
        tx.write(dsl -> dsl.deleteFrom(PRODUCT_FAMILY).execute());
    }

    @Test
    void aCreatedFamilyIsActiveAndCarriesTheSuppliedCodeAndName() {
        ProductFamily created = service.create("DEPOSITS", "Deposit products");

        assertThat(created.status()).isEqualTo(FamilyStatus.ACTIVE);
        assertThat(created.familyCode().value()).isEqualTo("DEPOSITS");
        assertThat(created.name()).isEqualTo("Deposit products");
        assertThat(service.findById(created.id())).isEqualTo(created);
    }

    @Test
    void bothInstantsComeFromTheInjectedClockAndAreEqualAtCreation() {
        ProductFamily created = service.create("DEPOSITS", "Deposit products");

        assertThat(created.createdAt()).isEqualTo(created.updatedAt());
        assertThat(created.createdAt())
                .as("the instant must be one this test's clock handed out")
                .isBetween(ORIGIN, ORIGIN.plus(1, ChronoUnit.MINUTES));
    }

    @Test
    void theIdentifierIsAssignedByTheServiceAndIsNotDerivedFromTheFamilyCode() {
        ProductFamily first = service.create("DEPOSITS", "Deposit products");
        tx.write(dsl -> dsl.deleteFrom(PRODUCT_FAMILY).execute());
        ProductFamily second = service.create("DEPOSITS", "Deposit products");

        // primary-keys, "The one ban": a key derived from another identifier is banned outright.
        // The same family code twice must not produce the same identifier.
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void aMalformedFamilyCodeIsRefusedBeforeAnythingIsPersisted() {
        List<String> refused = List.of("ab", "lowercase", "WITH-DASH", "WITH_UNDERSCORE",
                "WITH SPACE", "TWENTYONECHARACTERSX1", "");

        for (String candidate : refused) {
            CatalogFailure failure = catchThrowableOfType(
                    CatalogFailure.class, () -> service.create(candidate, "a name"));
            assertThat(failure).as("family code %s must be refused", candidate).isNotNull();
            assertThat(failure.code()).isEqualTo(ErrorCode.FAMILY_CODE_INVALID);
        }

        assertThat(persistedRowCount()).isZero();
    }

    @Test
    void theShortestAndLongestAdmissibleFamilyCodesAreAccepted() {
        assertThat(service.create("ABC", "three").familyCode().value()).isEqualTo("ABC");
        assertThat(service.create("A0123456789012345678", "twenty").familyCode().value())
                .isEqualTo("A0123456789012345678");
    }

    @Test
    void anEmptyOrOverLongNameIsRefusedBeforeAnythingIsPersisted() {
        CatalogFailure empty =
                catchThrowableOfType(CatalogFailure.class, () -> service.create("DEPOSITS", ""));
        CatalogFailure overLong = catchThrowableOfType(
                CatalogFailure.class, () -> service.create("DEPOSITS", "x".repeat(121)));

        assertThat(empty.code()).isEqualTo(ErrorCode.FAMILY_NAME_INVALID);
        assertThat(overLong.code()).isEqualTo(ErrorCode.FAMILY_NAME_INVALID);
        assertThat(persistedRowCount()).isZero();
    }

    @Test
    void theShortestAndLongestAdmissibleNamesAreAccepted() {
        assertThat(service.create("SHORTNAME", "x").name()).isEqualTo("x");
        assertThat(service.create("LONGNAME", "y".repeat(120)).name()).hasSize(120);
    }

    @Test
    void aFamilyCodeAPersistedFamilyAlreadyHoldsIsRefused() {
        service.create("DEPOSITS", "first");

        CatalogFailure failure = catchThrowableOfType(
                CatalogFailure.class, () -> service.create("DEPOSITS", "second"));

        assertThat(failure.code()).isEqualTo(ErrorCode.FAMILY_CODE_DUPLICATE);
        assertThat(persistedRowCount()).isOne();
    }

    @Test
    void anIdentifierNoFamilyHoldsIsRefusedOnRead() {
        CatalogFailure failure = catchThrowableOfType(
                CatalogFailure.class,
                () -> service.findById(UUID.fromString("0192f3a1-0000-7000-8000-0000000000ff")));

        assertThat(failure.code()).isEqualTo(ErrorCode.FAMILY_NOT_FOUND);
    }

    @Test
    void anIdentifierNoFamilyHoldsIsRefusedOnRetire() {
        CatalogFailure failure = catchThrowableOfType(
                CatalogFailure.class,
                () -> service.retire(UUID.fromString("0192f3a1-0000-7000-8000-0000000000fe")));

        assertThat(failure.code()).isEqualTo(ErrorCode.FAMILY_NOT_FOUND);
    }

    @Test
    void retiringAnActiveFamilyTransitionsItAndStampsTheClock() {
        ProductFamily created = service.create("DEPOSITS", "Deposit products");

        ProductFamily retired = service.retire(created.id());

        assertThat(retired.status()).isEqualTo(FamilyStatus.RETIRED);
        assertThat(retired.id()).isEqualTo(created.id());
        assertThat(retired.familyCode()).isEqualTo(created.familyCode());
        assertThat(retired.createdAt()).isEqualTo(created.createdAt());
        assertThat(retired.updatedAt()).isAfter(created.updatedAt());
    }

    @Test
    void retiringARetiredFamilyReturnsItUnchangedIncludingItsUpdatedAt() {
        ProductFamily created = service.create("DEPOSITS", "Deposit products");
        ProductFamily firstRetire = service.retire(created.id());

        ProductFamily secondRetire = service.retire(created.id());

        assertThat(secondRetire).isEqualTo(firstRetire);
        assertThat(secondRetire.updatedAt())
                .as("the persisted instant, never one stamped from the clock on the idempotent path")
                .isEqualTo(firstRetire.updatedAt());
    }

    @Test
    void noOperationTakesAFamilyOutOfRetired() {
        ProductFamily created = service.create("DEPOSITS", "Deposit products");
        service.retire(created.id());

        service.retire(created.id());
        service.retire(created.id());

        assertThat(service.findById(created.id()).status()).isEqualTo(FamilyStatus.RETIRED);
        // The service exposes create, findById, list and retire. None of them writes ACTIVE after
        // insert, and none of them accepts a family code for an existing family (FR-012, FR-013).
        assertThat(List.of(ProductFamilyService.class.getDeclaredMethods()).stream()
                        .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                        .map(java.lang.reflect.Method::getName)
                        .sorted()
                        .toList())
                .containsExactly("create", "findById", "list", "retire");
    }

    @Test
    void aRetiredFamilyKeepsTheFamilyCodeItWasCreatedWith() {
        ProductFamily created = service.create("DEPOSITS", "Deposit products");

        ProductFamily retired = service.retire(created.id());

        assertThat(retired.familyCode()).isEqualTo(created.familyCode());
        assertThat(service.findById(created.id()).familyCode()).isEqualTo(created.familyCode());
    }

    @Test
    void twoConcurrentRetiresProduceOneTransitionAndOneUpdatedAt() throws Exception {
        ProductFamily created = service.create("DEPOSITS", "Deposit products");
        CyclicBarrier bothReady = new CyclicBarrier(2);

        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> retireAfter(bothReady, created.id()));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> retireAfter(bothReady, created.id()));

        ProductFamily one = first.get();
        ProductFamily other = second.get();

        // Which call won the race is not asserted — that would be a dependence on unguaranteed
        // ordering. What is asserted is that there was exactly one transition: both callers see
        // RETIRED, and both see the same updatedAt.
        assertThat(one.status()).isEqualTo(FamilyStatus.RETIRED);
        assertThat(other.status()).isEqualTo(FamilyStatus.RETIRED);
        assertThat(one.updatedAt()).isEqualTo(other.updatedAt());
        assertThat(one).isEqualTo(other);
    }

    private int persistedRowCount() {
        Integer count = tx.read(dsl -> dsl.fetchCount(PRODUCT_FAMILY));
        return count;
    }

    private ProductFamily retireAfter(CyclicBarrier bothReady, UUID id) {
        try {
            bothReady.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (java.util.concurrent.BrokenBarrierException broken) {
            throw new IllegalStateException(broken);
        }
        return service.retire(id);
    }
}
