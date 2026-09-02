package mn.netgroup.cb.productcatalog.support;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The one throwaway PostgreSQL every integration test in this repository runs against, and the
 * property wiring that points an application context at it.
 *
 * <p>java-backend-rules, "Integration tests run against real PostgreSQL": the real committed
 * migrations, applied by the application's own Flyway configuration, to
 * {@code postgres:16.15-alpine}. No in-memory substitute.
 */
public final class CatalogPostgres {

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>("postgres:16.15-alpine");

    static {
        CONTAINER.start();
    }

    private CatalogPostgres() {}

    /**
     * Points a context at the container, and hands it a <b>freshly generated</b> cursor sealing
     * key. No key literal exists anywhere in this repository (NFR-003), and the property is
     * required with no default in {@code application.yml}, so a forgotten key is a startup
     * failure rather than a silent fallback.
     */
    public static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", CONTAINER::getUsername);
        registry.add("spring.datasource.password", CONTAINER::getPassword);
        registry.add("catalog.cursor.active-key-id", () -> "test");
        registry.add("catalog.cursor.keys.test", CatalogPostgres::aFreshSealingKey);
    }

    private static String aFreshSealingKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
