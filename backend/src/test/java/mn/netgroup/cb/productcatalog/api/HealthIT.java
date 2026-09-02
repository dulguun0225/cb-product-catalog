package mn.netgroup.cb.productcatalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import mn.netgroup.cb.productcatalog.support.PostgresBackedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 001:FR-014 — "The service shall expose a health operation reporting whether it is able to serve
 * requests."
 *
 * <p>java-backend-observability, "Autoconfigured telemetry needs a probe test": the datasource
 * indicator is registered by autoconfiguration, and autoconfigured telemetry that silently fails
 * to register leaves a green build and a blind deployment. This is that probe — it runs against a
 * real database, so an UP it reports is one the datasource actually answered for.
 *
 * <p>It is also the runtime conformance test for the health operation the committed contract
 * declares (lld D-05, §7). Without it, the drift gate proves only that the document equals
 * itself. The {@code Accept} of {@code *}{@code /}{@code *} case is the one that matters: Actuator's default produced-types
 * list carries the vendor media type first, so a contract declaring {@code application/json}
 * would be false unless the {@code EndpointMediaTypes} bean makes it true.
 */
class HealthIT extends PostgresBackedTest {

    @Autowired MockMvc http;

    @Test
    void theHealthOperationReportsUpAgainstARealDatabase() throws Exception {
        MockHttpServletResponse response = health();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("status").asText()).isEqualTo("UP");
    }

    @Test
    void aClientSendingAcceptAnythingReceivesApplicationJson() throws Exception {
        MockHttpServletResponse response = health();

        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentType())
                .as("the vendor media type must not be what a wildcard Accept resolves to")
                .doesNotContain("vnd.spring-boot.actuator");
    }

    @Test
    void theBodyCarriesTheOneMemberTheContractDeclaresAndNoComponentDetail() throws Exception {
        JsonNode body = new ObjectMapper()
                .readTree(health().getContentAsString(StandardCharsets.UTF_8));

        // show-details and show-components are both never: the health operation reads no family
        // data and exposes no component detail (spec §2, "It reads no family data").
        assertThat(body.fieldNames()).toIterable().containsExactly("status");
    }

    private MockHttpServletResponse health() throws Exception {
        return http.perform(MockMvcRequestBuilders.get("/actuator/health").accept(MediaType.ALL))
                .andReturn()
                .getResponse();
    }
}
