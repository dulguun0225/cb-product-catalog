package mn.netgroup.cb.productcatalog.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import mn.netgroup.cb.productcatalog.support.PostgresBackedServerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;

/**
 * 001:FR-023 — "IF an unhandled failure occurs while serving a request, THEN the service shall
 * respond with a 500 problem document carrying error code INTERNAL_ERROR and a correlation
 * identifier as its only internal detail."
 *
 * <p>The requirement calls the identifier a detail the response carries. java-backend-observability,
 * "The correlation id in an error response resolves to a log event", is what stops that being a
 * dead end: an identifier that retrieves nothing makes the requirement useless. This test reads
 * the identifier out of a real 500 body and asserts a log event carries it.
 *
 * <p>It is driven through a <b>throwing servlet filter</b>, not a throwing handler (lld D-07). A
 * failure raised in a filter never reaches {@code @RestControllerAdvice}; it lands on the
 * {@code /error} dispatch, which MockMvc does not perform, so this test needs a real port. A
 * version of this test driven only through a throwing handler would pass while the filter path
 * still returned the framework's own uncoded, identifier-less body.
 */
@Import(CorrelationIdIT.ThrowingFilterConfiguration.class)
class CorrelationIdIT extends PostgresBackedServerTest {

    /** The path the filter below refuses to let through. */
    private static final String DETONATOR = "/v1/product-families?detonate=yes";

    @TestConfiguration
    static class ThrowingFilterConfiguration {
        @Bean
        FilterRegistrationBean<Filter> detonator() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new Filter() {
                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                        throws IOException, ServletException {
                    if ("yes".equals(request.getParameter("detonate"))) {
                        throw new ServletException("sentinel-filter-failure-do-not-leak");
                    }
                    chain.doFilter(request, response);
                }
            });
            // After the correlation-id filter, so the identifier has been minted.
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            return registration;
        }
    }

    @Autowired TestRestTemplate http;

    private ListAppender<ILoggingEvent> captured;
    private ch.qos.logback.classic.Logger errorLogLogger;

    @BeforeEach
    void captureLogEvents() {
        errorLogLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ErrorLog.class);
        captured = new ListAppender<>();
        captured.start();
        errorLogLogger.addAppender(captured);
        errorLogLogger.setLevel(Level.ERROR);
    }

    @AfterEach
    void stopCapturing() {
        errorLogLogger.detachAppender(captured);
        captured.stop();
    }

    @Test
    void aFailureRaisedInAServletFilterStillAnswersWithACodedProblemDocument() throws Exception {
        ResponseEntity<String> response = http.getForEntity(DETONATOR, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.hasNonNull("correlationId")).isTrue();
    }

    @Test
    void theCorrelationIdentifierInTheBodyRetrievesTheLogEventForThatFailure() throws Exception {
        ResponseEntity<String> response = http.getForEntity(DETONATOR, String.class);

        String correlationId =
                new ObjectMapper().readTree(response.getBody()).get("correlationId").asText();

        assertThat(captured.list)
                .as("the identifier the body returned must retrieve a log event")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains(correlationId);
                    assertThat(event.getMDCPropertyMap()).containsEntry("correlation_id", correlationId);
                });
    }

    @Test
    void theFilterFailuresOwnMessageNeverReachesTheBody() {
        ResponseEntity<String> response = http.getForEntity(DETONATOR, String.class);

        assertThat(response.getBody()).doesNotContain("sentinel-filter-failure-do-not-leak");
        assertThat(response.getBody()).doesNotContain("ServletException");
    }
}
