package mn.netgroup.cb.productcatalog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mn.netgroup.cb.productcatalog.api.error.ErrorCode;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What the committed contract says about this service, in the form the generator reads.
 *
 * <p>java-backend-api, "One committed OpenAPI document, generated and diffed": the document is
 * generated from this application and byte-compared against {@code backend/contracts/openapi.yaml}
 * by {@code OpenApiContractDriftTest}. A difference is fixed <b>in the code</b>; the committed file
 * is only ever replaced by a generated one.
 *
 * <p>lld D-05. Springdoc's own actuator rendering stays off — it mangles the {@code operationId}
 * for uniqueness and emits vendor media types, both of which move under a springdoc bump and would
 * flap the drift gate. The one owned customizer below puts {@code /actuator/health} into the
 * document instead. And the {@code EndpointMediaTypes} bean is what makes the declared
 * {@code application/json} <em>true</em>: Actuator's default produced-types list carries
 * {@code application/vnd.spring-boot.actuator.v3+json} first, so a client sending a wildcard
 * {@code Accept} would otherwise receive the vendor type and a hand-written contract would be
 * false.
 *
 * <p>lld D-06. {@code x-requirements} is stamped on every operation from <b>one immutable map</b>,
 * not from annotations — kept because it is the only mechanism that reaches the annotation-less
 * health path. A map keyed by {@code operationId} is fail-open, so the drift test also asserts
 * non-empty coverage, that every key resolves to an operation, and that the union equals the
 * requirement set the design names.
 */
@Configuration
public class OpenApiConfig {

    /** lld D-06's map. The one place an operation's requirement list is written. */
    public static final Map<String, List<String>> REQUIREMENTS = Map.of(
            "createProductFamily",
                    List.of("FR-001", "FR-002", "FR-003", "FR-004", "FR-016", "FR-017", "FR-018", "FR-023"),
            "getProductFamily", List.of("FR-005", "FR-019", "FR-023"),
            "listProductFamilies",
                    List.of("FR-006", "FR-007", "FR-008", "FR-009", "FR-020", "FR-021", "FR-022", "FR-023"),
            "retireProductFamily", List.of("FR-010", "FR-011", "FR-013", "FR-019", "FR-023"),
            "health", List.of("FR-014"));

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String JSON = "application/json";

    private static final String DESCRIPTION =
            """
            The core-banking product family catalog. A product family carries an opaque \
            identifier assigned by the service, a client-supplied immutable family code, a \
            name, and a lifecycle status of ACTIVE or RETIRED. Families are created, \
            read by identifier, listed a keyset page at a time, and retired. Retirement is \
            terminal and idempotent; no operation returns a family to ACTIVE and no \
            operation changes a persisted family code. This service declares no \
            authentication: it returns no 401 and no 403 (spec 1, OI-005).

            Named gap, open at this gate: the protocol-level failures the framework \
            produces before any operation below is entered - 400 on a body that is not \
            readable JSON, 405, 406, 415 - are RFC 9457 problem documents with the right \
            status, but the approved error-code catalog has no member for them, so they \
            carry no code member. They are therefore not declared as responses on the \
            operations below. See the design's OI-006 and decision D-08.""";

    @Bean
    public EndpointMediaTypes endpointMediaTypes() {
        return new EndpointMediaTypes(List.of(JSON), List.of(JSON));
    }

    @Bean
    public OpenAPI productCatalogOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Product family catalog")
                        .version("0.1.0")
                        .description(DESCRIPTION))
                .tags(List.of(
                        new Tag()
                                .name("Product families")
                                .description(
                                        "Registration, retrieval, listing and retirement of product"
                                                + " families."),
                        new Tag().name("Operations").description("Deployment readiness.")))
                .components(new Components()
                        .addParameters("FamilyId", familyIdParameter())
                        .addResponses("FamilyNotFound", familyNotFoundResponse())
                        .addResponses("InternalError", internalErrorResponse())
                        .addSchemas("ErrorCode", errorCodeSchema())
                        .addSchemas("Problem", problemSchema())
                        .addSchemas("HealthStatus", healthStatusSchema()));
    }

    /**
     * The one customizer: the health operation, the requirement stamps, and the removal of the
     * generator's own ambient additions.
     */
    @Bean
    public OpenApiCustomizer catalogDocumentCustomizer() {
        return document -> {
            // The generated server URL carries a random test port and is not part of the contract.
            document.setServers(null);

            document.getPaths().addPathItem("/actuator/health", healthPathItem());

            // Springdoc inlines an enum wherever it is used and names no component for it, and it
            // rebuilds components.schemas from the types it saw. Both schemas the contract names
            // but no request or response type carries by reference are registered here, and the
            // inline uses are pointed at them.
            document.getComponents().addSchemas("HealthStatus", healthStatusSchema());
            document.getComponents().addSchemas("ProductFamilyStatus", productFamilyStatusSchema());
            referenceStatusSchema(document);

            stripDefaultHeaderStyle(document);
            hoistSharedPathParameters(document);
            stampRequirements(document);
        };
    }

    /**
     * Drops {@code style: simple} from response headers.
     *
     * <p>It is the OpenAPI default for a header, so the generator writing it out adds a token the
     * contract does not carry and that nothing reads.
     */
    private static void stripDefaultHeaderStyle(OpenAPI document) {
        document.getPaths().values().stream()
                .flatMap(pathItem -> pathItem.readOperations().stream())
                .filter(operation -> operation.getResponses() != null)
                .flatMap(operation -> operation.getResponses().values().stream())
                .filter(response -> response.getHeaders() != null)
                .flatMap(response -> response.getHeaders().values().stream())
                .forEach(header -> header.setStyle(null));
    }

    /** Points the two inline uses of the status enum at the one component that declares it. */
    private static void referenceStatusSchema(OpenAPI document) {
        Schema<?> reference = new Schema<>().$ref("#/components/schemas/ProductFamilyStatus");

        Schema<?> family = document.getComponents().getSchemas().get("ProductFamily");
        if (family != null && family.getProperties() != null) {
            family.getProperties().put("status", reference);
        }

        document.getPaths().values().stream()
                .flatMap(pathItem -> pathItem.readOperations().stream())
                .filter(operation -> operation.getParameters() != null)
                .flatMap(operation -> operation.getParameters().stream())
                .filter(parameter -> "status".equals(parameter.getName()))
                .forEach(parameter -> parameter.setSchema(reference));
    }

    private static Schema<?> productFamilyStatusSchema() {
        StringSchema schema = new StringSchema();
        schema.description("The two states of a product family. ACTIVE is initial; RETIRED is terminal.");
        List.of("ACTIVE", "RETIRED").forEach(schema::addEnumItemObject);
        return schema;
    }

    private static void stampRequirements(OpenAPI document) {
        document.getPaths().values().stream()
                .flatMap(pathItem -> pathItem.readOperations().stream())
                .forEach(operation -> {
                    List<String> requirements = REQUIREMENTS.get(operation.getOperationId());
                    if (requirements != null) {
                        operation.addExtension("x-requirements", requirements);
                    }
                });
    }

    /**
     * Moves a parameter every operation of a path declares identically up onto the path item.
     *
     * <p>Springdoc emits path parameters per operation. The committed contract declares the
     * family identifier once per path, which is the same contract said once instead of twice.
     */
    private static void hoistSharedPathParameters(OpenAPI document) {
        document.getPaths().values().forEach(pathItem -> {
            List<Operation> operations = pathItem.readOperations();
            if (operations.isEmpty()) {
                return;
            }
            List<Parameter> shared = new ArrayList<>();
            for (Parameter candidate : firstNonNull(operations.get(0).getParameters())) {
                boolean inEveryOperation = operations.stream()
                        .allMatch(operation -> firstNonNull(operation.getParameters()).stream()
                                .anyMatch(parameter -> sameParameter(parameter, candidate)));
                if (inEveryOperation && candidate.get$ref() != null) {
                    shared.add(candidate);
                }
            }
            if (shared.isEmpty()) {
                return;
            }
            // A $ref must stand alone: springdoc leaves the resolved name, in, required and schema
            // beside it, and a sibling of a $ref is not what the contract declares.
            List<Parameter> bare = shared.stream()
                    .map(parameter -> new Parameter().$ref(parameter.get$ref()))
                    .map(Parameter.class::cast)
                    .toList();
            pathItem.setParameters(new ArrayList<>(bare));
            operations.forEach(operation -> {
                List<Parameter> remaining = new ArrayList<>(firstNonNull(operation.getParameters()));
                remaining.removeIf(parameter ->
                        shared.stream().anyMatch(alreadyHoisted -> sameParameter(parameter, alreadyHoisted)));
                operation.setParameters(remaining.isEmpty() ? null : remaining);
            });
        });
    }

    private static boolean sameParameter(Parameter one, Parameter other) {
        return one.get$ref() != null && one.get$ref().equals(other.get$ref());
    }

    private static List<Parameter> firstNonNull(List<Parameter> parameters) {
        return parameters == null ? List.of() : parameters;
    }

    private static Parameter familyIdParameter() {
        Schema<?> identifier = new StringSchema().format("uuid");
        Parameter parameter = new Parameter()
                .name("id")
                .in("path")
                .required(true)
                .description("The opaque identifier the service assigned to the family.")
                .schema(identifier);
        parameter.setExamples(new LinkedHashMap<>(Map.of(
                "assigned",
                new io.swagger.v3.oas.models.examples.Example()
                        .value("0192f3a1-6c7d-7c3e-9f21-4b7c1d2e3f40"))));
        return parameter;
    }

    private static ApiResponse familyNotFoundResponse() {
        return new ApiResponse()
                .description(
                        "No persisted family holds that opaque identifier (FAMILY_NOT_FOUND). A path"
                                + " segment that is not a well-formed identifier is one no family holds"
                                + " and is answered here, with the same body as a well-formed identifier"
                                + " that is simply absent: every member that could discriminate the two"
                                + " is identical, and only instance differs, which is the caller's own"
                                + " request URI reflected back.")
                .content(problemContent());
    }

    private static ApiResponse internalErrorResponse() {
        return new ApiResponse()
                .description(
                        "An unhandled failure occurred (INTERNAL_ERROR). The body carries a"
                                + " correlation identifier and no other internal detail - no exception"
                                + " message, no class name, no stack frame.")
                .content(problemContent());
    }

    private static Content problemContent() {
        return new Content()
                .addMediaType(
                        PROBLEM_JSON,
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem")));
    }

    /**
     * The catalog, rendered from the {@link ErrorCode} enum itself.
     *
     * <p>Read off the enum rather than restated, so the document and the compile-checked catalog
     * cannot drift apart. The committed catalog snapshot governs the other half of the same fact.
     */
    private static Schema<?> errorCodeSchema() {
        StringSchema schema = new StringSchema();
        schema.description(
                "The closed catalog of machine-readable error codes. Adding, removing or re-typing a"
                        + " member is a change to this contract and to the committed catalog snapshot.");
        Arrays.stream(ErrorCode.values()).map(Enum::name).forEach(schema::addEnumItemObject);
        return schema;
    }

    private static Schema<?> problemSchema() {
        Schema<Object> problem = new Schema<>();
        problem.addType("object");
        problem.setDescription(
                "An RFC 9457 problem document. Every error response declared on the operations in"
                        + " this document has this shape, is built in one place, and carries a code from"
                        + " one catalog, so a client may branch on code alone. Clients branch on code"
                        + " and never on title or detail prose. Unknown members are ignored by RFC 9457,"
                        + " so a later code or parameter is an additive change. Scope, stated because"
                        + " the difference is not visible from the schema: the framework-produced"
                        + " failures named in info.description - 400 on a body that is not readable"
                        + " JSON, 405, 406, 415 - are problem documents of this shape with no code"
                        + " member, and they are not declared as responses on any operation here. A"
                        + " generated client must not assume that every problem+json body it can"
                        + " receive satisfies this schema until the design's OI-006 is answered.");
        problem.setRequired(List.of("status", "code"));

        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("type", new StringSchema().format("uri")._default("about:blank"));
        properties.put(
                "title", new StringSchema().description("Human-readable prose. Not stable; never branch on it."));
        properties.put("status", integerSchema(null));
        properties.put(
                "detail",
                new StringSchema().description("Human-readable prose. Not stable; never branch on it."));
        properties.put("instance", new StringSchema().format("uri-reference"));
        properties.put("code", new Schema<>().$ref("#/components/schemas/ErrorCode"));
        properties.put(
                "correlationId",
                new StringSchema()
                        .format("uuid")
                        .description(
                                "Present on INTERNAL_ERROR only. The identifier that retrieves the log"
                                        + " event for this failure, and the only internal detail a 500"
                                        + " exposes."));
        StringSchema violation = new StringSchema();
        violation.description(
                "Present on LIMIT_ABOVE_MAXIMUM only. Which bound the limit broke, so a client library"
                        + " can repair the request rather than retry it unchanged.");
        List.of("ABOVE_MAX", "BELOW_MIN", "NOT_AN_INTEGER").forEach(violation::addEnumItemObject);
        properties.put("violation", violation);
        properties.put(
                "min",
                integerSchema("Present on LIMIT_ABOVE_MAXIMUM only. The lowest limit the contract admits."));
        properties.put(
                "max",
                integerSchema("Present on LIMIT_ABOVE_MAXIMUM only. The highest limit the contract admits."));
        problem.setProperties(properties);
        return problem;
    }

    private static Schema<?> integerSchema(String description) {
        Schema<Object> schema = new Schema<>();
        schema.addType("integer");
        schema.setFormat("int32");
        schema.setDescription(description);
        return schema;
    }

    private static Schema<?> healthStatusSchema() {
        Schema<Object> health = new Schema<>();
        health.addType("object");
        health.setDescription("The readiness of the service, as Spring Boot Actuator reports it.");
        health.setRequired(List.of("status"));
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("status", new StringSchema());
        health.setProperties(properties);
        return health;
    }

    /** FR-014, rendered by hand because springdoc's actuator rendering is deliberately off. */
    private static PathItem healthPathItem() {
        Content healthContent = new Content()
                .addMediaType(
                        JSON,
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/HealthStatus")));

        Operation health = new Operation()
                .addTagsItem("Operations")
                .operationId("health")
                .summary("Report whether the service is able to serve requests")
                .description(
                        "Spring Boot Actuator health, with the datasource indicator on. Read by the"
                                + " container orchestrator's probe and by the deploy stage. It returns no"
                                + " family data.")
                .responses(new ApiResponses()
                        .addApiResponse(
                                "200",
                                new ApiResponse()
                                        .description("The service is able to serve requests.")
                                        .content(healthContent))
                        .addApiResponse(
                                "503",
                                new ApiResponse()
                                        .description("A dependency the service needs is not available.")
                                        .content(healthContent)));

        return new PathItem().get(health);
    }
}
