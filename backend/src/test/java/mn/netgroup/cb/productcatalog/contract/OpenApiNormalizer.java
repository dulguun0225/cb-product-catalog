package mn.netgroup.cb.productcatalog.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one hand-owned canonical normaliser.
 *
 * <p>java-backend-api, "One hand-owned canonical normalizer": recursive key sort, pinned
 * array-element order, LF line endings, trailing newline. <b>The generator's own ordering is not
 * trusted as stable</b>, including any order-by-keys option it offers — springdoc's output
 * ordering is documented as non-deterministic run to run.
 *
 * <p>Which arrays are pinned, and why only those. A JSON Schema {@code required} list is a
 * <b>set</b>: the generator emits it in one order and a hand-written document in another, and
 * neither is more correct, so it is sorted. Every other array in this document is ordered —
 * {@code enum} members, {@code tags}, {@code x-requirements}, a path's parameters — and sorting
 * one would destroy information the contract carries. Sorting everything is the tempting version
 * of this rule and it is wrong.
 *
 * <p>Hand-owned rather than delegated on purpose: a flapping diff gate gets relaxed, and a relaxed
 * gate masks the drift it exists to catch.
 */
public final class OpenApiNormalizer {

    /** Arrays whose element order carries no meaning, and which are therefore sorted. */
    private static final List<String> UNORDERED_ARRAYS = List.of("required");

    /**
     * The provenance header, part of the canonical form.
     *
     * <p>Both sides of the diff go through this normaliser, so a fixed header costs nothing in
     * byte identity and buys a committed file that explains itself to the next agent to open it.
     */
    private static final String HEADER =
            """
            # PLATFORM-14 · contract (OpenAPI 3.1) · Код + тест · 2026-09-02
            # GENERATED. Do not edit by hand.
            #
            # This document is produced by the running application and written through the one
            # hand-owned normaliser, backend/src/test/java/mn/netgroup/cb/productcatalog/contract/
            # OpenApiNormalizer. OpenApiContractDriftTest regenerates it twice, under two different
            # default time zones and locales, and fails ./mvnw verify on any byte difference against
            # this file. A difference is fixed in the code, never by editing this file to match.
            #
            # It is structurally identical - paths, operations, parameters, schemas, responses and
            # error codes - to the contract approved at the design gate for 001-product-family-catalog.
            """;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE));

    private OpenApiNormalizer() {}

    /** Reads a generated document and returns its one canonical form. */
    public static String normalize(String generated) {
        try {
            JsonNode canonical = canonicalise(YAML.readTree(generated), null);
            String rendered = YAML.writerWithDefaultPrettyPrinter().writeValueAsString(canonical);
            return HEADER + rendered.replace("\r\n", "\n").stripTrailing() + "\n";
        } catch (com.fasterxml.jackson.core.JacksonException unreadable) {
            throw new IllegalArgumentException("the generated document is not readable YAML", unreadable);
        }
    }

    private static JsonNode canonicalise(JsonNode node, String fieldName) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode sorted = YAML.createObjectNode();
            for (String name : names) {
                sorted.set(name, canonicalise(node.get(name), name));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<JsonNode> elements = new ArrayList<>();
            node.forEach(element -> elements.add(canonicalise(element, null)));
            if (UNORDERED_ARRAYS.contains(fieldName)) {
                elements.sort(java.util.Comparator.comparing(JsonNode::toString));
            }
            ArrayNode array = YAML.createArrayNode();
            elements.forEach(array::add);
            return array;
        }
        return node;
    }

    /** The document as a map, for structural assertions that should not read YAML text. */
    public static Map<String, Object> parse(String document) {
        try {
            return YAML.readValue(document, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JacksonException unreadable) {
            throw new IllegalArgumentException("the document is not readable YAML", unreadable);
        }
    }
}
