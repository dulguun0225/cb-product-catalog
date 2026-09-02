package mn.netgroup.cb.productcatalog.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import mn.netgroup.cb.productcatalog.api.error.CatalogFailure;
import mn.netgroup.cb.productcatalog.api.error.ErrorCode;
import mn.netgroup.cb.productcatalog.api.error.LimitViolation;
import mn.netgroup.cb.productcatalog.domain.ProductFamily;
import mn.netgroup.cb.productcatalog.domain.ProductFamilyService;
import mn.netgroup.cb.productcatalog.ids.FamilyIds;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only class in this repository that knows HTTP for product families.
 *
 * <p>The API version is a URL path segment, {@code /v1} (java-backend-api, "The API version is a
 * URL path segment"): no header or date pipeline selects a contract per request, and there is no
 * runtime version-transformation module. <b>There is no {@code @PatchMapping} anywhere</b> —
 * {@code PATCH} is banned on every endpoint, because JSON Merge Patch reads a {@code null} member
 * as "delete this field".
 */
@RestController
@Tag(name = "Product families")
@RequestMapping(value = "/v1/product-families", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductFamilyController {

    /** FR-020's declared bounds. Also stated on the contract's limit parameter. */
    static final String LIMIT_DEFAULT = "20";

    private final ProductFamilyService service;
    private final KeysetPager pager;
    private final FamilyIds ids;

    public ProductFamilyController(ProductFamilyService service, KeysetPager pager, FamilyIds ids) {
        this.service = service;
        this.pager = pager;
        this.ids = ids;
    }

    /** FR-001, FR-002, FR-003, FR-004, FR-016, FR-017, FR-018. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(
            responseCode = "201",
            description = "The family was persisted.",
            headers =
                    @io.swagger.v3.oas.annotations.headers.Header(
                            name = "Location",
                            required = true,
                            description = "The created family, addressed by its opaque identifier.",
                            schema = @Schema(type = "string", format = "uri-reference")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductFamilyView.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "The family code is not 3 to 20 characters of A-Z and 0-9 (FAMILY_CODE_INVALID),"
                            + " or the name is not 1 to 120 characters (FAMILY_NAME_INVALID).",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(
            responseCode = "409",
            description = "A persisted family already holds that family code (FAMILY_CODE_DUPLICATE).",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(responseCode = "500", ref = "#/components/responses/InternalError")
    @Operation(
            operationId = "createProductFamily",
            summary = "Register a product family",
            description =
                    "Persists a new product family with status ACTIVE and an opaque identifier the"
                            + " service assigns. The family code is stored exactly as supplied and can"
                            + " never be changed afterwards.")
    public ResponseEntity<ProductFamilyView> create(@RequestBody CreateProductFamilyRequest request) {
        ProductFamily created = service.create(request.familyCode(), request.name());
        // FR-004: the Location header addresses the family by its opaque identifier. The family
        // code is not a URL identifier and never appears in a path segment.
        return ResponseEntity.created(URI.create("/v1/product-families/" + created.id()))
                .body(ProductFamilyView.of(created));
    }

    /** FR-005, FR-019. */
    @GetMapping("/{id}")
    @ApiResponse(
            responseCode = "200",
            description = "The family.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductFamilyView.class)))
    @ApiResponse(responseCode = "404", ref = "#/components/responses/FamilyNotFound")
    @ApiResponse(responseCode = "500", ref = "#/components/responses/InternalError")
    @Operation(
            operationId = "getProductFamily",
            summary = "Read one product family by its opaque identifier",
            description =
                    "Returns the family addressed by the opaque identifier. The family code is not"
                            + " an identifier in a URL and cannot address a family here.")
    public ProductFamilyView get(
            @Parameter(ref = "#/components/parameters/FamilyId") @PathVariable("id") String id) {
        return ProductFamilyView.of(service.findById(identifierOf(id)));
    }

    /** FR-006, FR-007, FR-008, FR-009, FR-020, FR-021, FR-022. */
    @GetMapping
    @ApiResponse(
            responseCode = "200",
            description = "One page of product families.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductFamilyPageView.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "The limit is not an integer between 1 and 100 (LIMIT_ABOVE_MAXIMUM, with a"
                            + " violation member of ABOVE_MAX, BELOW_MIN or NOT_AN_INTEGER and the min"
                            + " and max members that bound it), the cursor failed its integrity check or"
                            + " was issued for a different sort specification (CURSOR_INVALID), or the"
                            + " status filter is neither ACTIVE nor RETIRED (STATUS_FILTER_INVALID).",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(responseCode = "500", ref = "#/components/responses/InternalError")
    @Operation(
            operationId = "listProductFamilies",
            summary = "List product families, one keyset page at a time",
            description =
                    "Returns one keyset page ordered by familyCode ascending, with the opaque"
                            + " identifier appended as the final tiebreak only. The identifier is not"
                            + " part of the declared sort vocabulary, and the relative order of two"
                            + " families sharing a family code is an explicit non-promise of this"
                            + " contract - nothing may depend on it. There is no offset, page or"
                            + " pageNumber parameter and no total count. nextCursor is null only on"
                            + " the last page, and a non-null nextCursor always fetches a further"
                            + " page. A cursor is issued by the service, is integrity-sealed, and"
                            + " carries the sort specification it was issued for; clients pass one"
                            + " back unmodified and never construct or edit one. This page is not a"
                            + " snapshot: a family created after the first page may appear on a later"
                            + " one. With no status parameter, families of every status are returned"
                            + " (spec OI-003).")
    public ProductFamilyPageView list(
            @Parameter(description = "Return only families whose status equals this value.")
                    @RequestParam(name = "status", required = false)
                    ProductFamilyStatus status,
            @Parameter(
                            description =
                                    "The maximum number of families in the page. It must be an integer"
                                            + " between 1 and 100. Any value the declared bounds do not"
                                            + " admit - above the maximum, below the minimum,"
                                            + " absent-but-present as an empty value, repeated, not an"
                                            + " integer, or beyond the range of a 32-bit integer - is"
                                            + " rejected with 400 LIMIT_ABOVE_MAXIMUM carrying a"
                                            + " violation member that says which. It is never silently"
                                            + " clamped to the maximum and never silently defaulted.")
                    @RequestParam(name = "limit", required = false, defaultValue = LIMIT_DEFAULT)
                    @Min(LimitViolation.MINIMUM)
                    @Max(LimitViolation.MAXIMUM)
                    int limit,
            @Parameter(
                            description =
                                    "The nextCursor of the previous page, passed back unmodified. It is"
                                            + " integrity-sealed and carries the key identifier it was"
                                            + " sealed under, so the sealing key can be rotated without"
                                            + " invalidating cursors already issued. It is sealed, not"
                                            + " confidential - a client can read the sort specification"
                                            + " and the last row's values out of it, and cannot forge one"
                                            + " - and forging one would grant no read the caller does not"
                                            + " already have.",
                            schema = @Schema(type = "string", maxLength = 512))
                    @RequestParam(name = "cursor", required = false)
                    String cursor,
            HttpServletRequest request) {
        rejectALimitTheFrameworkWouldHaveDefaulted(request);
        return pager.page(status == null ? null : status.toDomain(), cursor, limit);
    }

    /**
     * Two {@code limit} values the framework's own binding accepts, and FR-020 does not.
     *
     * <p>An empty {@code limit=} is treated by Spring as an absent parameter, and a repeated
     * {@code limit=5&limit=7} binds to the first value — so both would <b>silently default</b>,
     * which is the behaviour the contract names and forbids beside silent clamping. Neither
     * reaches a binding failure, so neither reaches the advice; they are caught here, against the
     * raw parameter values, before the bound one is used.
     */
    private static void rejectALimitTheFrameworkWouldHaveDefaulted(HttpServletRequest request) {
        String[] supplied = request.getParameterValues("limit");
        if (supplied == null) {
            return; // genuinely absent: the declared default applies
        }
        if (supplied.length > 1 || supplied[0].isBlank()) {
            throw LimitViolation.failure(LimitViolation.NOT_AN_INTEGER);
        }
    }

    /** FR-010, FR-011, FR-013, FR-019. */
    @PostMapping("/{id}/retire")
    @ApiResponse(
            responseCode = "200",
            description = "The family, now RETIRED.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductFamilyView.class)))
    @ApiResponse(responseCode = "404", ref = "#/components/responses/FamilyNotFound")
    @ApiResponse(responseCode = "500", ref = "#/components/responses/InternalError")
    @Operation(
            operationId = "retireProductFamily",
            summary = "Retire a product family",
            description =
                    "Transitions an ACTIVE family to RETIRED. RETIRED is terminal: a retire request"
                            + " against an already RETIRED family is idempotent and returns that"
                            + " family unchanged, including its updatedAt. There is no request body"
                            + " and no operation that returns a family to ACTIVE.")
    public ProductFamilyView retire(
            @Parameter(ref = "#/components/parameters/FamilyId") @PathVariable("id") String id) {
        return ProductFamilyView.of(service.retire(identifierOf(id)));
    }

    /**
     * Reads the {@code {id}} path segment.
     *
     * <p>lld D-03. The variable binds as a {@code String} and {@code FamilyIds.parse} decides; a
     * segment that is not a well-formed identifier is one no persisted family holds, and FR-019
     * answers that with {@code 404 FAMILY_NOT_FOUND} — the same answer, and the same body, as a
     * well-formed identifier that is simply absent. A 400 that fired only on a non-identifier
     * segment would be an oracle for the identifier's form, and the identifier is opaque.
     */
    UUID identifierOf(String segment) {
        return ids.parse(segment).orElseThrow(() -> new CatalogFailure(ErrorCode.FAMILY_NOT_FOUND));
    }
}
