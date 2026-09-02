package mn.netgroup.cb.productcatalog.persistence;

import java.util.function.Function;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.postgresql.util.PSQLException;
import org.springframework.stereotype.Component;

/**
 * The one transaction seam. All SQL in this repository is reached through it.
 *
 * <p>java-backend-rules, "SQL is reached only through the one transaction seam": code touches
 * SQL only inside a lambda-scoped transaction block that receives the context as a parameter,
 * and <b>read-only intent is the method name, never an annotation</b> — hence {@link #read} and
 * {@link #write} rather than {@code @Transactional(readOnly = true)}, which is on the
 * runtime-silent ban list.
 *
 * <p><b>{@code DSLContext} is not an injectable bean.</b> Spring Boot's jOOQ autoconfiguration
 * is excluded in {@code ProductCatalogApplication} so that no {@code DSLContext} bean exists in
 * the context at all; this class builds its own from the {@code DataSource}. An injected
 * {@code DSLContext} used outside a block runs in autocommit and commits each statement on its
 * own, invisibly — banning the injection makes the unscoped query unwritable rather than merely
 * reviewed against.
 *
 * <p>Records are detached repo-wide by {@code Settings.withAttachRecords(false)}, so jOOQ's own
 * runtime-silent CRUD — {@code store()}, {@code insert()}, {@code update()}, {@code delete()},
 * {@code refresh()} — throws rather than guessing which columns to write from in-memory record
 * state (java-backend-rules, "jOOQ's own runtime-silent CRUD is banned").
 *
 * <p>Isolation is PostgreSQL's default, READ COMMITTED, and is deliberately not raised. At
 * REPEATABLE READ the loser of two concurrent retires gets a serialization failure instead of
 * zero affected rows, which turns an idempotent retire into an error the caller must retry
 * (lld D-10).
 */
@Component
public final class Tx {

    private final DSLContext root;

    public Tx(DataSource dataSource) {
        this.root = DSL.using(dataSource, SQLDialect.POSTGRES, settings());
    }

    /** The settings the seam imposes on every statement it renders. */
    public static Settings settings() {
        return new Settings().withAttachRecords(false).withRenderSchema(false);
    }

    /** One transaction whose intent is to read. */
    public <T> T read(Function<DSLContext, T> body) {
        return inOneTransaction(body);
    }

    /** One transaction whose intent is to write. */
    public <T> T write(Function<DSLContext, T> body) {
        return inOneTransaction(body);
    }

    private <T> T inOneTransaction(Function<DSLContext, T> body) {
        try {
            return root.transactionResult(configuration -> body.apply(configuration.dsl()));
        } catch (DataAccessException failure) {
            throw translate(failure);
        }
    }

    /**
     * jOOQ's exception types are translated here, at the seam's boundary, so no layer above
     * imports one. An integrity violation keeps its constraint name, because that name is what
     * FR-018's mapping branches on.
     */
    private static RuntimeException translate(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres) {
                var serverMessage = postgres.getServerErrorMessage();
                if (serverMessage != null && serverMessage.getConstraint() != null) {
                    return new ConstraintViolation(serverMessage.getConstraint(), failure);
                }
            }
        }
        return new PersistenceFailure(failure);
    }
}
