package mn.netgroup.cb.productcatalog.api;

import java.util.List;
import mn.netgroup.cb.productcatalog.domain.FamilyCode;
import mn.netgroup.cb.productcatalog.domain.FamilyStatus;
import mn.netgroup.cb.productcatalog.domain.ProductFamily;
import mn.netgroup.cb.productcatalog.domain.ProductFamilyService;
import org.springframework.stereotype.Component;

/**
 * The one class that renders a paginated query.
 *
 * <p>java-backend-api, "Keyset pagination only". There is no {@code offset}, no {@code page} and
 * no {@code pageNumber} — not in the contract and not in the query — because under concurrent
 * inserts an offset silently skips and duplicates rows.
 *
 * <p>The total order is {@code (family_code, id)}: a declared business sort column first, with
 * the identifier appended as the <b>final tiebreak only</b>. primary-keys, "A time-ordered key is
 * not an ordering", grants that one carve-out under four constraints, and all four hold here —
 * the tiebreak is never the leading key, the identifier appears in no declared sort vocabulary
 * (there is no {@code sort} parameter at all), the ordered statement lives in one named seam
 * ({@code ProductFamilyRepository#findPage}, which is what the ban-list test scopes the exemption
 * to), and the relative order of two families sharing a family code is an explicit non-promise of
 * the contract.
 *
 * <p><b>A keyset page is not a snapshot.</b> A family created after the first page may appear on
 * a later one. What the unique total order buys is immunity to skipping and duplication, and
 * nothing more.
 */
@Component
public final class KeysetPager {

    private final ProductFamilyService service;
    private final CursorCodec cursors;

    public KeysetPager(ProductFamilyService service, CursorCodec cursors) {
        this.service = service;
        this.cursors = cursors;
    }

    /**
     * One page.
     *
     * <p>FR-009: {@code limit + 1} rows are asked for, and the presence of that extra row — and
     * only that — issues a {@code nextCursor}. Otherwise it is null. That is what makes "null
     * means this was the last page" true rather than merely likely.
     */
    public ProductFamilyPageView page(FamilyStatus status, String rawCursor, int limit) {
        CursorCodec.Position seek =
                rawCursor == null ? null : cursors.decode(rawCursor, CursorCodec.SORT_SPEC);
        FamilyCode afterCode = seek == null ? null : seek.familyCode();

        List<ProductFamily> fetched = service.list(
                status, afterCode, seek == null ? null : seek.id(), limit + 1);

        boolean aFurtherPageExists = fetched.size() > limit;
        List<ProductFamily> page = aFurtherPageExists ? fetched.subList(0, limit) : fetched;

        String nextCursor = null;
        if (aFurtherPageExists) {
            ProductFamily last = page.get(page.size() - 1);
            nextCursor = cursors.encode(new CursorCodec.Position(last.familyCode(), last.id()));
        }

        return new ProductFamilyPageView(page.stream().map(ProductFamilyView::of).toList(), nextCursor);
    }
}
