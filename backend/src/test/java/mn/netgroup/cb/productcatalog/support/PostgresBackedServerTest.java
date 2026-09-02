package mn.netgroup.cb.productcatalog.support;

import mn.netgroup.cb.productcatalog.ProductCatalogApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An integration test that drives the application over a real HTTP port.
 *
 * <p>Needed wherever the servlet container's own behaviour is the thing under test — the
 * {@code /error} dispatch above all, which MockMvc does not perform (lld D-07).
 */
@SpringBootTest(
        classes = ProductCatalogApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresBackedServerTest {

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        CatalogPostgres.wire(registry);
    }
}
