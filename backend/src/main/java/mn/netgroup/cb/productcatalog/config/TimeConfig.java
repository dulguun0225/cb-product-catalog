package mn.netgroup.cb.productcatalog.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one time source domain code reads.
 *
 * <p>java-backend-rules, "{@code Clock} is injected": wall-clock reads in domain code are
 * banned — {@code Instant.now()}, {@code LocalDate.now()}, {@code new Date()},
 * {@code System.currentTimeMillis()}. This bean is the only replacement, and the ban's
 * store-language half is discharged in the migration, which carries no {@code DEFAULT now()}
 * and no trigger.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
