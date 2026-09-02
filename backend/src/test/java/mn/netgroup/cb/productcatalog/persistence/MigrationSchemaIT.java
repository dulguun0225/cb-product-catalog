package mn.netgroup.cb.productcatalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The committed migration applied to a real PostgreSQL, per java-backend-rules
 * "Integration tests run against real PostgreSQL" — no in-memory substitute.
 *
 * <p>001:FR-018 — "IF a create-family request carries a family code a persisted family
 * already holds, THEN the service shall reject the request with a 409 problem document
 * carrying error code FAMILY_CODE_DUPLICATE." Detection is the database's, never a pre-read,
 * because a pre-read races; the unique index is therefore part of the requirement's
 * machinery and is asserted here by name. The 409 itself is asserted in
 * {@code ProductFamilyCreateReadIT}.
 *
 * <p>001:FR-013 — "The service shall expose no operation that transitions a product family
 * out of RETIRED." The check constraint bounds the column to the two declared states, so no
 * write of any third value can succeed; that the two declared states admit no exit is
 * asserted in {@code ProductFamilyRetireIT}.
 *
 * <p>001:FR-003 — "WHEN the service persists or changes a product family, the service shall
 * record the created-at and updated-at instants from the injected clock." A column default or
 * a trigger would be a wall-clock read in the store's language; this test asserts that
 * neither column carries a default, so the injected clock is the only possible writer.
 */
@Testcontainers
class MigrationSchemaIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15-alpine");

    @BeforeAll
    static void applyTheCommittedMigration() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void theFamilyCodeUniqueIndexExistsByName() throws SQLException {
        List<String> indexes = queryColumn(
                "select indexname from pg_indexes where tablename = 'product_family'");

        assertThat(indexes).contains("ux_product_family_code");
    }

    @Test
    void theFamilyCodeUniqueIndexIsUniqueAndOnFamilyCodeAlone() throws SQLException {
        List<String> definitions = queryColumn(
                "select indexdef from pg_indexes where indexname = 'ux_product_family_code'");

        assertThat(definitions).singleElement().asString()
                .contains("CREATE UNIQUE INDEX")
                .contains("(family_code)");
    }

    @Test
    void theStatusCheckConstraintExistsByNameAndAdmitsExactlyTheTwoDeclaredStates() throws SQLException {
        List<String> definitions = queryColumn(
                "select pg_get_constraintdef(oid) from pg_constraint "
                        + "where conname = 'ck_product_family_status'");

        assertThat(definitions).singleElement().asString()
                .contains("ACTIVE")
                .contains("RETIRED");
    }

    @Test
    void aThirdStatusValueIsRefusedByTheDatabase() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into product_family (id, family_code, name, status, created_at, updated_at) "
                                + "values ('0192f3a1-0000-7000-8000-000000000003', 'DRAFTED', 'n', 'DRAFT', now(), now())")) {
            assertThat(catchSqlState(statement)).isEqualTo("23514"); // check_violation
        }
    }

    @Test
    void aDuplicateFamilyCodeIsRefusedByTheDatabase() throws SQLException {
        try (Connection connection = connect(); Statement seed = connection.createStatement()) {
            seed.execute("insert into product_family "
                    + "(id, family_code, name, status, created_at, updated_at) values "
                    + "('0192f3a1-0000-7000-8000-000000000001', 'DUPES', 'first', 'ACTIVE', now(), now())");

            try (PreparedStatement second = connection.prepareStatement(
                    "insert into product_family (id, family_code, name, status, created_at, updated_at) "
                            + "values ('0192f3a1-0000-7000-8000-000000000002', 'DUPES', 'second', 'ACTIVE', "
                            + "now(), now())")) {
                assertThat(catchSqlState(second)).isEqualTo("23505"); // unique_violation
            }
        }
    }

    @Test
    void neitherTimestampColumnCarriesADefaultAndNeitherDoesTheKey() throws SQLException {
        List<String> defaulted = queryColumn(
                "select column_name from information_schema.columns "
                        + "where table_name = 'product_family' and column_default is not null");

        assertThat(defaulted).isEmpty();
    }

    @Test
    void noTriggerWritesToTheTable() throws SQLException {
        List<String> triggers = queryColumn(
                "select tgname from pg_trigger t join pg_class c on c.oid = t.tgrelid "
                        + "where c.relname = 'product_family' and not t.tgisinternal");

        assertThat(triggers).isEmpty();
    }

    private static String catchSqlState(PreparedStatement statement) {
        try {
            statement.execute();
            return "no failure at all";
        } catch (SQLException expected) {
            return expected.getSQLState();
        }
    }

    private static Connection connect() throws SQLException {
        return java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static List<String> queryColumn(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
