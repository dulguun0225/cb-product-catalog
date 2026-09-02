package mn.netgroup.cb.productcatalog.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import mn.netgroup.cb.productcatalog.support.PostgresBackedServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The regenerate-and-diff gate.
 *
 * <p>java-backend-api, "One committed OpenAPI document, generated and diffed": the document is
 * generated from this running application, written through the one hand-owned normaliser, and
 * compared byte for byte against {@code backend/contracts/openapi.yaml}. <b>The diff is the
 * contract review.</b> A difference is fixed in the code; the committed file is only ever replaced
 * by a generated one.
 *
 * <p>"Authoritative generation runs on one operating system": the document is regenerated
 * <b>twice</b>, under two different default time zones and locales, and both regenerations must be
 * byte-identical to each other and to the committed copy. Byte identity is cheap to assert and a
 * flapping diff gate is worse than a strict one — a gate that flaps gets relaxed, and a relaxed
 * gate masks the drift it exists to catch. Recorded honestly: this repository has one build
 * environment and no CI host, so the cross-operating-system half of that rule is not wired (plan
 * §9), and this test varies what it can vary inside one JVM.
 *
 * <p>lld D-06. A map keyed by {@code operationId} is fail-open — a typo in a key silently stamps
 * nothing — so the coverage assertions below are what keep it honest: every operation carries a
 * non-empty {@code x-requirements}, every key of the owned map resolves to an operation that
 * exists, and the union of the ids equals the requirement set lld §4 names.
 *
 * <p>Named gap, stated because a green run here reads as more than it is: this gate proves the
 * document equals itself. That the <em>service</em> behaves as the document says is proved
 * elsewhere — by {@code HealthIT} for the health operation's runtime shape and media type, and by
 * the four operation integration tests for everything else. The conformance-fuzz oracle
 * java-backend-api asks for is not wired (plan §9).
 */
@TestPropertySource(properties = "springdoc.cache.disabled=true")
class OpenApiContractDriftTest extends PostgresBackedServerTest {

    private static final Path COMMITTED = Path.of("contracts/openapi.yaml");

    /** lld §4: the union of every operation's x-requirements, and exactly that. */
    private static final Set<String> DECLARED_REQUIREMENTS = new LinkedHashSet<>(List.of(
            "FR-001", "FR-002", "FR-003", "FR-004", "FR-005", "FR-006", "FR-007", "FR-008", "FR-009",
            "FR-010", "FR-011", "FR-013", "FR-014", "FR-016", "FR-017", "FR-018", "FR-019", "FR-020",
            "FR-021", "FR-022", "FR-023"));

    @Autowired TestRestTemplate http;

    @Test
    void twoRegenerationsUnderDifferentTimeZonesAndLocalesAreByteIdenticalToTheCommittedDocument()
            throws Exception {
        TimeZone originalZone = TimeZone.getDefault();
        Locale originalLocale = Locale.getDefault();

        String first;
        String second;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            first = OpenApiNormalizer.normalize(generate());

            TimeZone.setDefault(TimeZone.getTimeZone("America/Anchorage"));
            Locale.setDefault(Locale.forLanguageTag("ja-JP"));
            second = OpenApiNormalizer.normalize(generate());
        } finally {
            TimeZone.setDefault(originalZone);
            Locale.setDefault(originalLocale);
        }

        assertThat(first)
                .as("the generator must be deterministic across default time zone and locale")
                .isEqualTo(second);
        assertThat(first)
                .as(
                        "the committed contract at %s must be byte-identical to the generated one. "
                                + "Fix this in the code, never by editing the committed file to match.",
                        COMMITTED)
                .isEqualTo(Files.readString(COMMITTED, StandardCharsets.UTF_8));
    }

    @Test
    void everyOperationCarriesANonEmptyRequirementStamp() {
        operations().forEach((operationId, operation) -> {
            Object stamped = operation.get("x-requirements");
            assertThat(stamped)
                    .as("operation %s carries no x-requirements", operationId)
                    .isInstanceOf(List.class);
            assertThat((List<?>) stamped).as("operation %s carries an empty x-requirements", operationId)
                    .isNotEmpty();
        });
    }

    @Test
    void everyKeyOfTheOwnedMapResolvesToAnOperationThatExists() {
        Set<String> present = operations().keySet();

        assertThat(mn.netgroup.cb.productcatalog.config.OpenApiConfig.REQUIREMENTS.keySet())
                .as("an orphan key stamps nothing and the map would still look complete")
                .isSubsetOf(present);
        assertThat(present)
                .as("every operation in the document must be a key of the owned map")
                .containsExactlyInAnyOrderElementsOf(
                        mn.netgroup.cb.productcatalog.config.OpenApiConfig.REQUIREMENTS.keySet());
    }

    @Test
    void theUnionOfTheStampedRequirementsIsExactlyTheSetTheDesignNames() {
        Set<String> union = new LinkedHashSet<>();
        operations().values().forEach(operation ->
                ((List<?>) operation.get("x-requirements")).forEach(id -> union.add(String.valueOf(id))));

        assertThat(union).containsExactlyInAnyOrderElementsOf(DECLARED_REQUIREMENTS);
    }

    @Test
    void theDocumentDeclaresTheFiveOperationsAndNoOther() {
        assertThat(operations().keySet())
                .containsExactlyInAnyOrder(
                        "createProductFamily",
                        "getProductFamily",
                        "listProductFamilies",
                        "retireProductFamily",
                        "health");
    }

    @Test
    void theDocumentDeclaresNoOffsetPageOrPageNumberParameterAndNoPatchOperation() {
        Map<String, Object> document = OpenApiNormalizer.parse(generate());
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");

        List<String> parameterNames = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        paths.values().forEach(pathItem -> ((Map<?, ?>) pathItem).forEach((method, value) -> {
            if (value instanceof Map<?, ?> operation && !"parameters".equals(method)) {
                methods.add(String.valueOf(method));
                Object declared = operation.get("parameters");
                if (declared instanceof List<?> list) {
                    list.forEach(parameter -> {
                        Object name = ((Map<?, ?>) parameter).get("name");
                        if (name != null) {
                            parameterNames.add(String.valueOf(name));
                        }
                    });
                }
            }
        }));

        assertThat(parameterNames).doesNotContain("offset", "page", "pageNumber");
        assertThat(methods).doesNotContain("patch");
    }

    @Test
    void theDocumentDeclaresNoSortVocabularyThatCouldNameTheIdentifier() {
        // primary-keys, "A time-ordered key is not an ordering": the identifier must appear in no
        // declared sort vocabulary. This contract declares no sort parameter at all.
        Map<String, Object> document = OpenApiNormalizer.parse(generate());
        assertThat(document.toString()).doesNotContain("name: sort");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> operations() {
        Map<String, Object> document = OpenApiNormalizer.parse(generate());
        Map<String, Map<String, Object>> byOperationId = new java.util.LinkedHashMap<>();
        ((Map<String, Object>) document.get("paths"))
                .values()
                .forEach(pathItem -> ((Map<String, Object>) pathItem).forEach((method, value) -> {
                    if (value instanceof Map<?, ?> operation && operation.get("operationId") != null) {
                        byOperationId.put(
                                String.valueOf(operation.get("operationId")),
                                (Map<String, Object>) operation);
                    }
                }));
        return byOperationId;
    }

    private String generate() {
        return http.getForObject("/v3/api-docs.yaml", String.class);
    }
}
