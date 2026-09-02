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
 * The retire operation, over HTTP.
 *
 * <p>001:FR-010 — "WHILE a product family is ACTIVE, WHEN an API client submits a retire request
 * for that family, the service shall transition it to RETIRED."
 *
 * <p>001:FR-011 — "WHILE a product family is RETIRED, WHEN an API client submits a retire request
 * for that family, the service shall respond with that family unchanged." The whole body is
 * compared, {@code updatedAt} included: the requirement says <em>unchanged</em>, and a freshly
 * stamped instant would be a change.
 *
 * <p>001:FR-013 — "The service shall expose no operation that transitions a product family out of
 * RETIRED." Asserted as the absence of surface: repeated retires never leave RETIRED, and there
 * is no other mutating operation on a family.
 *
 * <p>001:FR-012 — "The service shall expose no operation that changes a persisted family code."
 * No operation on this controller accepts a family code after creation, and the retired body
 * carries the code the family was created with.
 *
 * <p>001:FR-019 — "IF a request addresses a product family by an opaque identifier no persisted
 * family holds, THEN the service shall reject the request with a 404 problem document carrying
 * error code FAMILY_NOT_FOUND."
 */
class ProductFamilyRetireIT extends PostgresBackedTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc http;
    @Autowired Tx tx;

    @BeforeEach
    void emptyTheTable() {
        tx.write(dsl -> dsl.deleteFrom(PRODUCT_FAMILY).execute());
    }

    @Test
    void retiringAnActiveFamilyIsTwoHundredAndTheFamilyIsRetired() throws Exception {
        JsonNode created = create("DEPOSITS", "Deposit products");

        MockHttpServletResponse response = retire(created.get("id").asText());

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = bodyOf(response);
        assertThat(body.get("status").asText()).isEqualTo("RETIRED");
        assertThat(body.get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(body.get("createdAt").asText()).isEqualTo(created.get("createdAt").asText());
    }

    @Test
    void theRetiredFamilyIsWhatASubsequentReadReturns() throws Exception {
        JsonNode created = create("DEPOSITS", "Deposit products");
        JsonNode retired = bodyOf(retire(created.get("id").asText()));

        MockHttpServletResponse read = http.perform(
                        MockMvcRequestBuilders.get("/v1/product-families/" + created.get("id").asText()))
                .andReturn()
                .getResponse();

        assertThat(bodyOf(read)).isEqualTo(retired);
    }

    @Test
    void retiringARetiredFamilyReturnsAnIdenticalBodyIncludingUpdatedAt() throws Exception {
        JsonNode created = create("DEPOSITS", "Deposit products");
        JsonNode first = bodyOf(retire(created.get("id").asText()));

        MockHttpServletResponse second = retire(created.get("id").asText());

        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(bodyOf(second)).isEqualTo(first);
        assertThat(bodyOf(second).get("updatedAt").asText())
                .as("the persisted instant, not a fresh one")
                .isEqualTo(first.get("updatedAt").asText());
    }

    @Test
    void retiringManyTimesNeverLeavesRetired() throws Exception {
        JsonNode created = create("DEPOSITS", "Deposit products");
        JsonNode first = bodyOf(retire(created.get("id").asText()));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(bodyOf(retire(created.get("id").asText()))).isEqualTo(first);
        }

        assertThat(first.get("status").asText()).isEqualTo("RETIRED");
    }

    @Test
    void aRetiredFamilyKeepsTheFamilyCodeItWasCreatedWith() throws Exception {
        JsonNode created = create("DEPOSITS", "Deposit products");

        JsonNode retired = bodyOf(retire(created.get("id").asText()));

        assertThat(retired.get("familyCode").asText()).isEqualTo("DEPOSITS");
        assertThat(retired.get("name").asText()).isEqualTo(created.get("name").asText());
    }

    @Test
    void anIdentifierNoFamilyHoldsIsFourHundredAndFourWithItsCode() throws Exception {
        MockHttpServletResponse response = retire("0192f3a1-0000-7000-8000-0000000000ff");

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(bodyOf(response).get("code").asText()).isEqualTo("FAMILY_NOT_FOUND");
    }

    @Test
    void aSegmentThatIsNotAWellFormedIdentifierGetsTheSameFourHundredAndFour() throws Exception {
        MockHttpServletResponse response = retire("not-an-identifier");

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(bodyOf(response).get("code").asText()).isEqualTo("FAMILY_NOT_FOUND");
    }

    @Test
    void thereIsNoPatchOperationAnywhereOnAFamily() throws Exception {
        JsonNode created = create("DEPOSITS", "Deposit products");

        MockHttpServletResponse patched = http.perform(
                        MockMvcRequestBuilders.patch("/v1/product-families/" + created.get("id").asText())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"familyCode\":\"CHANGED\"}"))
                .andReturn()
                .getResponse();
        MockHttpServletResponse put = http.perform(
                        MockMvcRequestBuilders.put("/v1/product-families/" + created.get("id").asText())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"familyCode\":\"CHANGED\"}"))
                .andReturn()
                .getResponse();

        // FR-012 and FR-013 hold by the absence of surface: no operation accepts a family code
        // after creation and none writes ACTIVE, so neither request is served.
        assertThat(patched.getStatus()).isNotIn(200, 201, 204);
        assertThat(put.getStatus()).isNotIn(200, 201, 204);
        assertThat(bodyOf(read(created.get("id").asText())).get("familyCode").asText())
                .isEqualTo("DEPOSITS");
    }

    private JsonNode create(String familyCode, String name) throws Exception {
        String body = JSON.writeValueAsString(new CreateProductFamilyRequest(familyCode, name));
        return bodyOf(http.perform(MockMvcRequestBuilders.post("/v1/product-families")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse());
    }

    /** Reads a family back, used to confirm nothing above changed it. */
    private MockHttpServletResponse read(String id) throws Exception {
        return http.perform(MockMvcRequestBuilders.get("/v1/product-families/" + id))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse retire(String id) throws Exception {
        return http.perform(MockMvcRequestBuilders.post("/v1/product-families/" + id + "/retire"))
                .andReturn()
                .getResponse();
    }

    private static JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
}
