package mn.netgroup.cb.productcatalog.api;

import static mn.netgroup.cb.productcatalog.generated.tables.ProductFamily.PRODUCT_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import mn.netgroup.cb.productcatalog.persistence.Tx;
import mn.netgroup.cb.productcatalog.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The create and read operations, over HTTP, against real PostgreSQL.
 *
 * <p>001:FR-001 — "WHEN an API client submits a create-family request whose body satisfies every
 * field constraint in §2, the service shall persist a new product family with status ACTIVE."
 *
 * <p>001:FR-004 — "WHEN the service has persisted a new product family, the service shall respond
 * 201 Created with a Location header addressing that family by its opaque identifier."
 *
 * <p>001:FR-005 — "WHEN an API client requests a product family by its opaque identifier, the
 * service shall respond with that family's opaque identifier, family code, name, status,
 * created-at and updated-at." All six, round-tripped.
 *
 * <p>001:FR-016 — "IF a create-family request carries a family code that is not 3 to 20
 * characters drawn from A–Z and 0–9, THEN the service shall reject the request with a 400 problem
 * document carrying error code FAMILY_CODE_INVALID."
 *
 * <p>001:FR-017 — "IF a create-family request carries a name that is not 1 to 120 characters,
 * THEN the service shall reject the request with a 400 problem document carrying error code
 * FAMILY_NAME_INVALID."
 *
 * <p>001:FR-018 — "IF a create-family request carries a family code a persisted family already
 * holds, THEN the service shall reject the request with a 409 problem document carrying error
 * code FAMILY_CODE_DUPLICATE." This is the only test that catches a rename of
 * {@code ux_product_family_code} in a later migration, which would silently degrade the 409 to a
 * 500 (plan §10).
 *
 * <p>001:FR-019 — "IF a request addresses a product family by an opaque identifier no persisted
 * family holds, THEN the service shall reject the request with a 404 problem document carrying
 * error code FAMILY_NOT_FOUND." Including a segment that is not a well-formed identifier at all:
 * that is an identifier no family holds, and the body must not differ (lld D-03).
 *
 * <p>001:FR-015 — "The service shall render every error response as a problem document carrying
 * an error code drawn from one catalog." Every failure below is asserted to be
 * {@code application/problem+json} with a {@code code} member.
 */
class ProductFamilyCreateReadIT extends PostgresBackedTest {

    @Autowired MockMvc http;
    @Autowired Tx tx;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void emptyTheTable() {
        tx.write(dsl -> dsl.deleteFrom(PRODUCT_FAMILY).execute());
    }

    @Test
    void aValidRequestIsPersistedAsActiveAndAnsweredWithTwoHundredAndOneAndALocationHeader()
            throws Exception {
        MockHttpServletResponse response = create("DEPOSITS", "Deposit products");

        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode body = bodyOf(response);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(response.getHeader("Location"))
                .isEqualTo("/v1/product-families/" + body.get("id").asText());
    }

    @Test
    void everyFieldSurvivesTheRoundTrip() throws Exception {
        JsonNode created = bodyOf(create("DEPOSITS", "Deposit products"));

        MockHttpServletResponse read = http.perform(
                        MockMvcRequestBuilders.get("/v1/product-families/" + created.get("id").asText()))
                .andReturn()
                .getResponse();

        assertThat(read.getStatus()).isEqualTo(200);
        JsonNode body = bodyOf(read);
        assertThat(body.get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(body.get("familyCode").asText()).isEqualTo("DEPOSITS");
        assertThat(body.get("name").asText()).isEqualTo("Deposit products");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("createdAt").asText()).isEqualTo(created.get("createdAt").asText());
        assertThat(body.get("updatedAt").asText()).isEqualTo(created.get("updatedAt").asText());
    }

    @Test
    void bothInstantsAreRfc3339InUtcWithAZDesignator() throws Exception {
        JsonNode created = bodyOf(create("DEPOSITS", "Deposit products"));

        assertThat(created.get("createdAt").asText()).endsWith("Z");
        assertThat(created.get("updatedAt").asText()).endsWith("Z");
        assertThat(created.get("createdAt").isTextual())
                .as("a numeric or epoch timestamp never appears on the wire")
                .isTrue();
        // Parseable as an RFC 3339 instant, which an epoch number would not be.
        assertThat(java.time.Instant.parse(created.get("createdAt").asText())).isNotNull();
    }

    @Test
    void theCreatedBodyCarriesExactlyTheSixDeclaredMembers() throws Exception {
        JsonNode created = bodyOf(create("DEPOSITS", "Deposit products"));

        assertThat(created.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("id", "familyCode", "name", "status", "createdAt", "updatedAt");
    }

    @Test
    void aMalformedFamilyCodeIsFourHundredWithItsCode() throws Exception {
        MockHttpServletResponse response = create("deposits", "Deposit products");

        assertProblem(response, 400, "FAMILY_CODE_INVALID");
    }

    @Test
    void anOverLongNameIsFourHundredWithItsCode() throws Exception {
        MockHttpServletResponse response = create("DEPOSITS", "x".repeat(121));

        assertProblem(response, 400, "FAMILY_NAME_INVALID");
    }

    @Test
    void anEmptyNameIsFourHundredWithItsCode() throws Exception {
        MockHttpServletResponse response = create("DEPOSITS", "");

        assertProblem(response, 400, "FAMILY_NAME_INVALID");
    }

    @Test
    void aFamilyCodeAlreadyHeldIsFourHundredAndNineWithItsCode() throws Exception {
        create("DEPOSITS", "first");

        MockHttpServletResponse response = create("DEPOSITS", "second");

        assertProblem(response, 409, "FAMILY_CODE_DUPLICATE");
    }

    @Test
    void anIdentifierNoFamilyHoldsIsFourHundredAndFourWithItsCode() throws Exception {
        MockHttpServletResponse response = http.perform(MockMvcRequestBuilders.get(
                        "/v1/product-families/0192f3a1-0000-7000-8000-0000000000ff"))
                .andReturn()
                .getResponse();

        assertProblem(response, 404, "FAMILY_NOT_FOUND");
    }

    @Test
    void aSegmentThatIsNotAWellFormedIdentifierGetsTheSameFourHundredAndFourAndTheSameBody()
            throws Exception {
        MockHttpServletResponse absent = http.perform(MockMvcRequestBuilders.get(
                        "/v1/product-families/0192f3a1-0000-7000-8000-0000000000ff"))
                .andReturn()
                .getResponse();
        MockHttpServletResponse malformed = http.perform(
                        MockMvcRequestBuilders.get("/v1/product-families/not-an-identifier"))
                .andReturn()
                .getResponse();
        MockHttpServletResponse percentEncoded = http.perform(
                        MockMvcRequestBuilders.get("/v1/product-families/%00%01"))
                .andReturn()
                .getResponse();

        assertProblem(malformed, 404, "FAMILY_NOT_FOUND");
        assertProblem(percentEncoded, 404, "FAMILY_NOT_FOUND");

        // Not an oracle for the identifier's form: every member that could discriminate the two
        // cases is identical. Only `instance` differs, and it is the caller's own request URI
        // reflected back — the committed contract's own FamilyNotFound example carries the path
        // segment there, and a caller learns nothing from being shown what it just sent.
        for (String member : java.util.List.of("type", "title", "status", "detail", "code")) {
            assertThat(bodyOf(malformed).get(member))
                    .as("member %s must not discriminate a malformed segment from an absent one", member)
                    .isEqualTo(bodyOf(absent).get(member));
        }
        java.util.List<String> absentMembers = new java.util.ArrayList<>();
        bodyOf(absent).fieldNames().forEachRemaining(absentMembers::add);
        assertThat(bodyOf(malformed).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrderElementsOf(absentMembers);
    }

    @Test
    void aFamilyCodeIsNotAnIdentifierInAUrl() throws Exception {
        create("DEPOSITS", "Deposit products");

        MockHttpServletResponse response = http.perform(
                        MockMvcRequestBuilders.get("/v1/product-families/DEPOSITS"))
                .andReturn()
                .getResponse();

        assertProblem(response, 404, "FAMILY_NOT_FOUND");
    }

    private MockHttpServletResponse create(String familyCode, String name) throws Exception {
        String body = JSON.writeValueAsString(new CreateProductFamilyRequest(familyCode, name));
        return http.perform(MockMvcRequestBuilders.post("/v1/product-families")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    private static JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertProblem(MockHttpServletResponse response, int status, String code)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = bodyOf(response);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
    }
}
