package mn.netgroup.cb.productcatalog.support;

import mn.netgroup.cb.productcatalog.ProductCatalogApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** An integration test that drives the application through MockMvc against real PostgreSQL. */
@SpringBootTest(classes = ProductCatalogApplication.class)
@AutoConfigureMockMvc
public abstract class PostgresBackedTest {

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        CatalogPostgres.wire(registry);
    }
}
