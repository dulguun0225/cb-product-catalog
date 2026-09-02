package mn.netgroup.cb.productcatalog.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One keyset page.
 *
 * <p>java-backend-api, "The list response shape": flat {@code {items, nextCursor}} — no data
 * envelope, no {@code _links} member, no total count. {@code nextCursor} is <b>null only on the
 * last page</b>, and a non-null value always fetches a further page (FR-009).
 */
@Schema(
        name = "ProductFamilyPage",
        requiredProperties = {"items", "nextCursor"},
        description =
                "One keyset page. The response is flat - there is no data envelope, no _links member"
                        + " and no total count.")
public record ProductFamilyPageView(
        List<ProductFamilyView> items,
        @Schema(
                        maxLength = 512,
                        nullable = true,
                        description =
                                "The cursor addressing the page after this one, or null when this is"
                                        + " the last page. Null means end; a non-null value always"
                                        + " fetches a further page.")
                String nextCursor) {}
