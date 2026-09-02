package mn.netgroup.cb.productcatalog.support;

import java.security.SecureRandom;
import java.util.Base64;
import mn.netgroup.cb.productcatalog.ProductCatalogApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The base every integration test in this repository extends.
 *
 * <p>java-backend-rules, "Integration tests run against real PostgreSQL": one throwaway
 * {@code postgres:16.15-alpine} container, the real committed migrations applied to it by the
 * application's own Flyway configuration. No in-memory substitute.
 *
 * <p>One container serves the whole JVM. Each test class gets a <b>freshly generated</b> cursor
 * sealing key, so no key literal exists anywhere in this repository (NFR-003) — the property is
 * required and has no default in {@code application.yml}, which is what makes a forgotten key a
 * startup failure rather than a silent fallback.
 */
@SpringBootTest(classes = ProductCatalogApplication.class)
public abstract class PostgresBackedTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.15-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("catalog.cursor.active-key-id", () -> "test");
        registry.add("catalog.cursor.keys.test", PostgresBackedTest::aFreshSealingKey);
    }

    private static String aFreshSealingKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
