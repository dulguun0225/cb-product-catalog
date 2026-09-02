package mn.netgroup.cb.productcatalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * NFR-002 — "share of application log lines that parse as a single-line JSON object: 100%,
 * every log line the service emits" (spec §4, NFR-002).
 *
 * <p>001:NFR-002. The threshold is 100%, so this test asserts on <em>every</em> captured
 * line, not on a sample. It reads the committed default; an environment variable can still
 * override {@code logging.structured.format.console} at runtime, which is the named gap
 * lld §7 records against this row.
 *
 * <p>001:FR-003 is covered here too, in its startup half: the requirement is that the
 * service record instants "from the injected clock", which presupposes an injectable
 * {@code Clock} bean exists. The wall-clock ban that keeps domain code off every other time
 * source is enforced in {@code BanListTest}.
 */
class StructuredLogFormatTest {

    @Configuration
    static class Minimal {}

    @Test
    void everyLogLineParsesAsOneJsonObject() throws Exception {
        PrintStream realOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        List<String> lines;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            try (ConfigurableApplicationContext ignored = bootMinimalContext()) {
                LoggerFactory.getLogger(StructuredLogFormatTest.class)
                        .info("a line emitted while the committed logging configuration is in force");
            }
        } finally {
            System.setOut(realOut);
        }

        lines = captured.toString(StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();

        assertThat(lines).as("the application must emit at least one log line to measure").isNotEmpty();

        ObjectMapper mapper = new ObjectMapper();
        for (String line : lines) {
            JsonNode parsed = mapper.readTree(line);
            assertThat(parsed.isObject())
                    .as("every log line must parse as a single JSON object, but this one did not: %s", line)
                    .isTrue();
        }
    }

    @Test
    void theClockIsAnInjectableBean() {
        try (ConfigurableApplicationContext context = bootMinimalContext()) {
            assertThat(context.getBean(Clock.class)).isNotNull();
        }
    }

    private ConfigurableApplicationContext bootMinimalContext() {
        SpringApplication application = new SpringApplication(Minimal.class, TimeConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run("--spring.main.banner-mode=off");
    }
}
