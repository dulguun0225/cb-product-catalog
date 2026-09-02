package mn.netgroup.cb.productcatalog.api;

import static mn.netgroup.cb.productcatalog.generated.tables.ProductFamily.PRODUCT_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import mn.netgroup.cb.productcatalog.domain.ProductFamilyService;
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
 * The list operation, its paging and its four rejections.
 *
 * <p>001:FR-006 — "WHEN an API client requests the family list with no status filter, the service
 * shall include families of every status in the result."
 *
 * <p>001:FR-007 — "WHEN an API client requests the family list with a status filter, the service
 * shall include only families whose status equals the filter value."
 *
 * <p>001:FR-008 — "WHEN an API client requests the family list, the service shall order the
 * result by family code ascending with the opaque identifier as the final tiebreak."
 *
 * <p>001:FR-009 — "WHEN an API client requests the family list, the service shall return a
 * non-null cursor only where a further page exists." Both halves: null on the last page, and a
 * non-null cursor that does fetch a further page.
 *
 * <p>001:FR-020 — "IF a list request carries a limit above the declared maximum, THEN the service
 * shall reject the request with a 400 problem document carrying error code LIMIT_ABOVE_MAXIMUM."
 * Rejected, never clamped: the page a rejected request would have returned is not returned.
 *
 * <p>001:FR-021 — "IF a list request carries a cursor that fails its integrity check or whose
 * sort specification does not match the request, THEN the service shall reject the request with a
 * 400 problem document carrying error code CURSOR_INVALID."
 *
 * <p>001:FR-022 — "IF a list request carries a status filter value that is neither ACTIVE nor
 * RETIRED, THEN the service shall reject the request with a 400 problem document carrying error
 * code STATUS_FILTER_INVALID."
 */
class ProductFamilyListIT extends PostgresBackedTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc http;
    @Autowired Tx tx;
    @Autowired ProductFamilyService service;
    @Autowired CursorCodec cursors;

    @BeforeEach
    void emptyTheTable() {
        tx.write(dsl -> dsl.deleteFrom(PRODUCT_FAMILY).execute());
    }

    @Test
    void twentyFiveFamiliesPageToExhaustionWithoutSkippingOrDuplicatingARow() throws Exception {
        List<String> seeded = seed(25);

        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = bodyOf(list(cursor == null ? "?limit=7" : "?limit=7&cursor=" + cursor));
            page.get("items").forEach(item -> paged.add(item.get("familyCode").asText()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
            assertThat(pages).as("paging must terminate").isLessThan(20);
        } while (cursor != null);

        assertThat(paged).containsExactlyElementsOf(seeded);
        assertThat(paged).doesNotHaveDuplicates();
        assertThat(pages).isEqualTo(4); // 7 + 7 + 7 + 4
    }

    @Test
    void nextCursorIsNullOnlyOnTheLastPage() throws Exception {
        seed(25);

        JsonNode firstPage = bodyOf(list("?limit=24"));
        assertThat(firstPage.get("nextCursor").isNull()).isFalse();

        JsonNode lastPage = bodyOf(list("?limit=24&cursor=" + firstPage.get("nextCursor").asText()));
        assertThat(lastPage.get("items")).hasSize(1);
        assertThat(lastPage.get("nextCursor").isNull())
                .as("null means end, and this is the end")
                .isTrue();
    }

    @Test
    void aNonNullCursorAlwaysFetchesAFurtherPage() throws Exception {
        seed(25);

        JsonNode page = bodyOf(list("?limit=25"));

        // Exactly 25 rows exist and 25 were asked for: a service that issued a cursor here would
        // be promising a page that does not exist.
        assertThat(page.get("items")).hasSize(25);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void aPageIsOrderedByFamilyCodeAscending() throws Exception {
        seed(25);

        JsonNode page = bodyOf(list("?limit=100"));

        List<String> codes = new ArrayList<>();
        page.get("items").forEach(item -> codes.add(item.get("familyCode").asText()));
        assertThat(codes).isSorted();
    }

    @Test
    void anUnfilteredPageCarriesFamiliesOfEveryStatus() throws Exception {
        List<String> seeded = seed(6);
        retire(seeded.get(0));
        retire(seeded.get(3));

        JsonNode page = bodyOf(list("?limit=100"));

        List<String> statuses = new ArrayList<>();
        page.get("items").forEach(item -> statuses.add(item.get("status").asText()));
        assertThat(page.get("items")).hasSize(6);
        assertThat(statuses).contains("ACTIVE", "RETIRED");
    }

    @Test
    void aFilteredPageCarriesOnlyFamiliesOfThatStatus() throws Exception {
        List<String> seeded = seed(6);
        retire(seeded.get(0));
        retire(seeded.get(3));

        JsonNode active = bodyOf(list("?limit=100&status=ACTIVE"));
        JsonNode retired = bodyOf(list("?limit=100&status=RETIRED"));

        assertThat(active.get("items")).hasSize(4);
        active.get("items").forEach(item -> assertThat(item.get("status").asText()).isEqualTo("ACTIVE"));
        assertThat(retired.get("items")).hasSize(2);
        retired.get("items").forEach(item -> assertThat(item.get("status").asText()).isEqualTo("RETIRED"));
    }

    @Test
    void aFilteredPageAlsoPagesToExhaustion() throws Exception {
        List<String> seeded = seed(9);
        for (int i = 0; i < 9; i += 2) {
            retire(seeded.get(i));
        }

        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            String query = "?limit=2&status=ACTIVE" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = bodyOf(list(query));
            page.get("items").forEach(item -> paged.add(item.get("familyCode").asText()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);

        assertThat(paged).hasSize(4).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void theDefaultLimitIsTwenty() throws Exception {
        seed(25);

        JsonNode page = bodyOf(list(""));

        assertThat(page.get("items")).hasSize(20);
    }

    @Test
    void aLimitAboveTheDeclaredMaximumIsRejectedAndNotClamped() throws Exception {
        seed(3);

        MockHttpServletResponse response = list("?limit=101");

        assertLimitProblem(response, "ABOVE_MAX");
        // Rejected, never clamped: no page came back at all.
        assertThat(bodyOf(response).has("items")).isFalse();
    }

    @Test
    void aLimitBelowTheDeclaredMinimumIsRejectedWithItsOwnViolation() throws Exception {
        MockHttpServletResponse response = list("?limit=0");

        assertLimitProblem(response, "BELOW_MIN");
    }

    @Test
    void aLimitThatIsNotAnIntegerIsRejected() throws Exception {
        assertLimitProblem(list("?limit=abc"), "NOT_AN_INTEGER");
    }

    @Test
    void anEmptyLimitIsRejected() throws Exception {
        assertLimitProblem(list("?limit="), "NOT_AN_INTEGER");
    }

    @Test
    void aRepeatedLimitIsRejected() throws Exception {
        assertLimitProblem(list("?limit=5&limit=7"), "NOT_AN_INTEGER");
    }

    @Test
    void aLimitBeyondTheRangeOfAThirtyTwoBitIntegerIsRejected() throws Exception {
        assertLimitProblem(list("?limit=2147483648"), "NOT_AN_INTEGER");
    }

    @Test
    void aTamperedCursorIsRejected() throws Exception {
        seed(25);
        String issued = bodyOf(list("?limit=5")).get("nextCursor").asText();
        String tampered = issued.substring(0, issued.length() - 4) + "AAAA";

        assertProblem(list("?limit=5&cursor=" + tampered), 400, "CURSOR_INVALID");
    }

    @Test
    void aCursorWhoseSealedPayloadWasEditedIsRejected() throws Exception {
        seed(25);
        String issued = bodyOf(list("?limit=5")).get("nextCursor").asText();
        String[] segments = issued.split("\\.");
        // Rewrite the last row's family code while keeping the original seal.
        segments[3] = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("FAM000".getBytes(StandardCharsets.UTF_8));

        assertProblem(list("?limit=5&cursor=" + String.join(".", segments)), 400, "CURSOR_INVALID");
    }

    @Test
    void aCursorIssuedUnderADifferentSortSpecificationIsRejected() throws Exception {
        // Sealed by this service, with a valid MAC, for a sort specification this request is not
        // for. It must be refused rather than turned into a best-effort seek.
        String otherSpec = cursors.encode(new CursorCodec.Position(
                new mn.netgroup.cb.productcatalog.domain.FamilyCode("FAM001"),
                java.util.UUID.fromString("0192f3a1-0000-7000-8000-000000000001")));
        String[] segments = otherSpec.split("\\.");
        segments[2] = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("name,id".getBytes(StandardCharsets.UTF_8));

        assertProblem(list("?cursor=" + String.join(".", segments)), 400, "CURSOR_INVALID");
    }

    @Test
    void aCursorSealedUnderAnUnknownKeyIsRejected() throws Exception {
        seed(25);
        String issued = bodyOf(list("?limit=5")).get("nextCursor").asText();
        String[] segments = issued.split("\\.");
        segments[1] = "rotated-away";

        assertProblem(list("?limit=5&cursor=" + String.join(".", segments)), 400, "CURSOR_INVALID");
    }

    @Test
    void aCursorThatIsNotACursorAtAllIsRejected() throws Exception {
        assertProblem(list("?cursor=nonsense"), 400, "CURSOR_INVALID");
        assertProblem(list("?cursor="), 400, "CURSOR_INVALID");
    }

    @Test
    void aStatusFilterOutsideTheTwoDeclaredValuesIsRejected() throws Exception {
        assertProblem(list("?status=DRAFT"), 400, "STATUS_FILTER_INVALID");
        assertProblem(list("?status=active"), 400, "STATUS_FILTER_INVALID");
    }

    @Test
    void thePageBodyIsFlatWithNoEnvelopeAndNoTotalCount() throws Exception {
        seed(2);

        JsonNode page = bodyOf(list(""));

        assertThat(page.fieldNames()).toIterable().containsExactlyInAnyOrder("items", "nextCursor");
    }

    @Test
    void thereIsNoOffsetOrPageParameterInTheContract() throws Exception {
        seed(25);

        // An unrecognised query parameter is ignored, so a client that sent one would silently
        // get page one. The point of asserting it is that offset paging is unreachable.
        JsonNode withOffset = bodyOf(list("?limit=5&offset=10"));
        JsonNode withPage = bodyOf(list("?limit=5&page=3"));
        JsonNode plain = bodyOf(list("?limit=5"));

        assertThat(withOffset.get("items").toString()).isEqualTo(plain.get("items").toString());
        assertThat(withPage.get("items").toString()).isEqualTo(plain.get("items").toString());
    }

    /** {@code FAM001} … {@code FAMnnn}, in ascending family-code order. */
    private List<String> seed(int howMany) {
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= howMany; i++) {
            String code = "FAM%03d".formatted(i);
            service.create(code, "family " + i);
            codes.add(code);
        }
        return codes;
    }

    /** Through the domain service: the retire operation is T-009's, and this test is T-008's. */
    private void retire(String familyCode) {
        service.list(null, null, null, 1000).stream()
                .filter(family -> family.familyCode().value().equals(familyCode))
                .findFirst()
                .ifPresentOrElse(
                        family -> service.retire(family.id()),
                        () -> {
                            throw new IllegalStateException("no family " + familyCode);
                        });
    }

    private MockHttpServletResponse list(String query) throws Exception {
        return http.perform(MockMvcRequestBuilders.get("/v1/product-families" + query))
                .andReturn()
                .getResponse();
    }

    private static JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertLimitProblem(MockHttpServletResponse response, String violation)
            throws Exception {
        assertProblem(response, 400, "LIMIT_ABOVE_MAXIMUM");
        JsonNode body = bodyOf(response);
        assertThat(body.get("violation").asText()).isEqualTo(violation);
        assertThat(body.get("min").asInt()).isEqualTo(1);
        assertThat(body.get("max").asInt()).isEqualTo(100);
    }

    private static void assertProblem(MockHttpServletResponse response, int status, String code)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(bodyOf(response).get("code").asText()).isEqualTo(code);
    }
}
