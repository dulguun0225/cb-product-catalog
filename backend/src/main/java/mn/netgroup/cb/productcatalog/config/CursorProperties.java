package mn.netgroup.cb.productcatalog.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The cursor sealing keys.
 *
 * <p>lld D-09. The payload carries the identifier of the key it was sealed under and this class
 * holds a <b>map</b> of keys, so the sealing key can be rotated without invalidating cursors
 * already issued. Without a key identifier the scheme has no rotation story, and a key that can
 * never be rotated forfeits by construction the property it is sealed for.
 *
 * <p>Both properties are required and neither has a default in {@code application.yml}: a
 * missing key is a startup failure, not a silent fallback to a literal committed in this
 * repository (NFR-003). Tests generate their own key per context.
 *
 * @param activeKeyId the key new cursors are sealed under
 * @param keys every key a cursor may have been sealed under, by identifier
 */
@Validated
@ConfigurationProperties("catalog.cursor")
public record CursorProperties(@NotEmpty String activeKeyId, @NotEmpty Map<String, String> keys) {}
