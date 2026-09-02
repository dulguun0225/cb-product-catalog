package mn.netgroup.cb.productcatalog.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import mn.netgroup.cb.productcatalog.ids.FamilyIds;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalog snapshot, and the leak test.
 *
 * <p>001:FR-015 — "The service shall render every error response as a problem document carrying
 * an error code drawn from one catalog." The snapshot is what makes "one catalog" checkable: it
 * pins every {@code (code, HTTP status, param-names)} triple, which is API surface a structural
 * OpenAPI diff cannot see. <b>Partial, and lld §8 says so</b> — the framework-produced protocol
 * failures (400 on an unreadable body, 405, 406, 415) have no member in the approved
 * eight-member catalog and so carry no code. That is D-08 and the design's OI-006, not a defect
 * introduced here.
 *
 * <p>001:FR-023 — "IF an unhandled failure occurs while serving a request, THEN the service shall
 * respond with a 500 problem document carrying error code INTERNAL_ERROR and a correlation
 * identifier as its only internal detail." "Only internal detail" is what the leak test asserts:
 * a sentinel message, the exception's class name and every stack frame must be absent from the
 * body.
 *
 * <p>001:FR-019 — "IF a request addresses a product family by an opaque identifier no persisted
 * family holds, THEN the service shall reject the request with a 404 problem document carrying
 * error code FAMILY_NOT_FOUND." Asserted here in the catalog's terms: the code's status is 404.
 */
class ErrorCatalogSnapshotTest {

    private static final String SENTINEL = "sentinel-9f2c1b7a-do-not-let-this-reach-the-wire";

    @RestController
    static class Throwing {
        @GetMapping("/v1/product-families/boom")
        String boom() {
            throw new IllegalStateException(SENTINEL);
        }
    }

    @Test
    void theCatalogMatchesItsCommittedSnapshot() throws Exception {
        List<String> committed = readCommittedSnapshot();

        List<String> live = Arrays.stream(ErrorCode.values())
                .map(code -> "%s|%d|%s"
                        .formatted(code.name(), code.status().value(), String.join(",", code.parameterNames())))
                .sorted()
                .toList();

        assertThat(live)
                .as("the committed snapshot is the diff target; regenerating it to make this pass "
                        + "is the failure it exists to prevent")
                .isEqualTo(committed);
    }

    @Test
    void everyCatalogMemberDeclaresTheStatusItsRequirementNames() {
        assertThat(ErrorCode.FAMILY_CODE_INVALID.status().value()).isEqualTo(400);
        assertThat(ErrorCode.FAMILY_NAME_INVALID.status().value()).isEqualTo(400);
        assertThat(ErrorCode.FAMILY_CODE_DUPLICATE.status().value()).isEqualTo(409);
        assertThat(ErrorCode.FAMILY_NOT_FOUND.status().value()).isEqualTo(404);
        assertThat(ErrorCode.LIMIT_ABOVE_MAXIMUM.status().value()).isEqualTo(400);
        assertThat(ErrorCode.CURSOR_INVALID.status().value()).isEqualTo(400);
        assertThat(ErrorCode.STATUS_FILTER_INVALID.status().value()).isEqualTo(400);
        assertThat(ErrorCode.INTERNAL_ERROR.status().value()).isEqualTo(500);
    }

    @Test
    void noExceptionMessageClassNameOrStackFrameReachesTheBodyOfAFiveHundred() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Throwing())
                .setControllerAdvice(new ProblemAdvice(new ProblemDocuments(), new ErrorLog(), new FamilyIds()))
                .addFilters(new mn.netgroup.cb.productcatalog.api.error.CorrelationIdFilter(new FamilyIds()))
                .build();

        String body = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/v1/product-families/boom"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(SENTINEL);
        assertThat(body).doesNotContain("IllegalStateException");
        assertThat(body).doesNotContain("java.lang");
        assertThat(body).doesNotContain("at mn.netgroup");
        assertThat(body).doesNotContain("Throwing");
    }

    @Test
    void theFiveHundredBodyCarriesTheCodeAndACorrelationIdentifierAndNothingElseInternal() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Throwing())
                .setControllerAdvice(new ProblemAdvice(new ProblemDocuments(), new ErrorLog(), new FamilyIds()))
                .addFilters(new CorrelationIdFilter(new FamilyIds()))
                .build();

        var response = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/v1/product-families/boom"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        var body = new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.get("status").asInt()).isEqualTo(500);
        assertThat(body.hasNonNull("correlationId")).isTrue();
        assertThat(body.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code", "correlationId");
    }

    private static List<String> readCommittedSnapshot() throws Exception {
        try (InputStream stream =
                ErrorCatalogSnapshotTest.class.getResourceAsStream("/error-catalog.snapshot")) {
            assertThat(stream).as("the committed snapshot must exist").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .sorted()
                    .toList();
        }
    }
}
