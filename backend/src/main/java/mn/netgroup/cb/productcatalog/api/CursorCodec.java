package mn.netgroup.cb.productcatalog.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mn.netgroup.cb.productcatalog.api.error.CatalogFailure;
import mn.netgroup.cb.productcatalog.api.error.ErrorCode;
import mn.netgroup.cb.productcatalog.config.CursorProperties;
import mn.netgroup.cb.productcatalog.domain.FamilyCode;
import mn.netgroup.cb.productcatalog.ids.FamilyIds;
import org.springframework.stereotype.Component;

/**
 * The one class that encodes or decodes a cursor.
 *
 * <p>java-backend-api, "Cursors are opaque, sealed, and carry their sort spec": a cursor that
 * fails its integrity check, <b>or whose sort specification no longer matches the request</b>, is
 * rejected with {@code 400 CURSOR_INVALID} and never decoded into a best-effort seek (FR-021).
 * Clients pass one back unmodified and never construct or edit one.
 *
 * <p><b>Stated honestly, because the two words are not the same: the cursor is sealed, not
 * opaque.</b> An HMAC gives integrity, not confidentiality. A client can read the sort
 * specification and the last row's values out of it — all values it just received in the response
 * body — and cannot forge one; forging one would grant no read the caller does not already have.
 * That is the design's OI-007, recorded rather than overstated.
 *
 * <p>Wire form: {@code v1.<keyId>.<sortSpec>.<familyCode>.<id>.<mac>}, every segment but the
 * version and the key identifier base64url-encoded without padding. The key identifier travels
 * with the cursor so the sealing key can rotate without invalidating cursors already issued
 * (D-09).
 *
 * <p><b>Structural validation runs whether or not the seal holds</b>, and independently of it: a
 * known version, a sort specification equal to the request's, a parseable identifier and a family
 * code within its declared bound are each required. A sealed payload is not a valid one.
 */
@Component
public final class CursorCodec {

    /** The sort specification this contract issues cursors for, as it appears on the wire. */
    public static final String SORT_SPEC = "familyCode,id";

    private static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int SEGMENTS = 6;

    private final CursorProperties keys;
    private final FamilyIds ids;

    public CursorCodec(CursorProperties keys, FamilyIds ids) {
        this.keys = keys;
        this.ids = ids;
    }

    /** The position of a page's last row, as a client receives it. */
    public record Position(FamilyCode familyCode, UUID id) {}

    public String encode(Position position) {
        String keyId = keys.activeKeyId();
        String body = body(keyId, SORT_SPEC, position.familyCode().value(), position.id().toString());
        return body + "." + encode(sign(keyId, body));
    }

    /**
     * Reads a cursor a client passed back.
     *
     * @param cursor the value the previous page's {@code nextCursor} carried
     * @param expectedSortSpec the sort specification this request is for
     * @throws CatalogFailure {@code CURSOR_INVALID} for anything that is not a cursor this service
     *     issued for this sort specification
     */
    public Position decode(String cursor, String expectedSortSpec) {
        String[] segments = cursor == null ? new String[0] : cursor.split("\\.", -1);
        if (segments.length != SEGMENTS || !VERSION.equals(segments[0])) {
            throw invalid();
        }

        String keyId = segments[1];
        String sortSpec = decodeSegment(segments[2]);
        String familyCode = decodeSegment(segments[3]);
        String rawId = decodeSegment(segments[4]);

        // Structural validation, independent of the seal.
        if (!expectedSortSpec.equals(sortSpec) || familyCode.length() > 20) {
            throw invalid();
        }
        UUID id = ids.parse(rawId).orElseThrow(CursorCodec::invalid);

        String secret = keys.keys().get(keyId);
        if (secret == null) {
            throw invalid();
        }
        byte[] presented = decodeBytes(segments[5]);
        byte[] expected = sign(keyId, body(keyId, sortSpec, familyCode, rawId));
        if (!MessageDigest.isEqual(presented, expected)) {
            throw invalid();
        }

        return new Position(new FamilyCode(familyCode), id);
    }

    private String body(String keyId, String sortSpec, String familyCode, String id) {
        return String.join(
                ".", VERSION, keyId, encode(bytes(sortSpec)), encode(bytes(familyCode)), encode(bytes(id)));
    }

    private byte[] sign(String keyId, String body) {
        String secret = keys.keys().get(keyId);
        if (secret == null) {
            throw invalid();
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(bytes(secret), ALGORITHM));
            return mac.doFinal(bytes(body));
        } catch (java.security.GeneralSecurityException unusableKey) {
            throw new IllegalStateException("the cursor sealing key is unusable", unusableKey);
        }
    }

    private static CatalogFailure invalid() {
        return new CatalogFailure(ErrorCode.CURSOR_INVALID);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String decodeSegment(String segment) {
        return new String(decodeBytes(segment), StandardCharsets.UTF_8);
    }

    private static byte[] decodeBytes(String segment) {
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException notBase64) {
            throw invalid();
        }
    }
}
