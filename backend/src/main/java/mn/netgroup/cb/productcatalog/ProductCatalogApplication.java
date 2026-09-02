package mn.netgroup.cb.productcatalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration;

/**
 * Entry point of the product family catalog service (CB_PRODUCT_CATALOG).
 *
 * <p>{@code JooqAutoConfiguration} is excluded so that <b>no {@code DSLContext} bean exists in
 * this application context at all</b>. java-backend-rules, "SQL is reached only through the one
 * transaction seam", requires that {@code DSLContext} not be an injectable bean; excluding the
 * autoconfiguration makes the unscoped query unwritable rather than merely banned by a
 * predicate. {@code persistence/Tx} builds its own context from the {@code DataSource}.
 */
@SpringBootApplication(exclude = JooqAutoConfiguration.class)
public class ProductCatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductCatalogApplication.class, args);
    }
}
