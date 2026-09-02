package mn.netgroup.cb.productcatalog.domain;

import java.util.regex.Pattern;
import mn.netgroup.cb.productcatalog.api.error.CatalogFailure;
import mn.netgroup.cb.productcatalog.api.error.ErrorCode;

/**
 * The human-facing handle for a family, and a value that cannot exist in an invalid form.
 *
 * <p>FR-016 — a family code is 3 to 20 characters drawn from {@code A}–{@code Z} and
 * {@code 0}–{@code 9}, and anything else is a 400 {@code FAMILY_CODE_INVALID}. {@link #of} is the
 * only factory, so there is no route to a {@code FamilyCode} that has not been through it.
 *
 * <p>business-numbering, and the clauses that are live here because this system does <b>not</b>
 * issue this number — the client supplies it:
 *
 * <ul>
 *   <li><i>Validate at every ingress, resolve the format by lookup, never by matching shape</i> —
 *       this factory is called at the ingress, before any repository call. A client-supplied
 *       number is exactly what makes this clause load-bearing.
 *   <li><i>Numbers are immutable, never reused, never reassigned, and stored exactly as issued</i>
 *       — one canonical case, no separators, not nullable, and no update statement anywhere
 *       targets the column.
 *   <li><i>Parsing meaning out of a number is banned everywhere</i> — nothing substrings,
 *       prefixes, regex-reads or {@code LIKE}-matches a family code, in source or in query text.
 *       The pattern below is a <b>validator</b>, not a reader: it answers whether the value is
 *       admissible and extracts nothing from it.
 * </ul>
 *
 * <p>The <i>check digit</i> clause is dormant on the issuance precondition, not on the
 * human-keyed one: a check digit is a terminal part of a format the issuer owns and cannot be
 * imposed on a value a client supplies. This validator carries the detection burden instead.
 */
public record FamilyCode(String value) {

    private static final Pattern ADMISSIBLE = Pattern.compile("[A-Z0-9]{3,20}");

    public static FamilyCode of(String raw) {
        if (raw == null || !ADMISSIBLE.matcher(raw).matches()) {
            throw new CatalogFailure(ErrorCode.FAMILY_CODE_INVALID);
        }
        return new FamilyCode(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
