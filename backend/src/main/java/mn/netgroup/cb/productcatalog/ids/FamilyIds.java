package mn.netgroup.cb.productcatalog.ids;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one type in this repository that produces an identifier, and the one that reads one off
 * the wire.
 *
 * <p>primary-keys, "Exactly one application-side producer, adopted rather than hand-rolled":
 * the bit layout is not written here — it comes from {@code java-uuid-generator}, a maintained
 * implementation — and {@code FamilyIdsGoldenTest} pins the layout it emits. The banned
 * version-4 generator {@code UUID.randomUUID()} is named in the ban-list predicate and appears
 * nowhere in this repository's main sources.
 *
 * <p>One generator instance serves every request. The generator is documented thread-safe,
 * which is what lets a single instance serve every virtual thread without a lock.
 *
 * <p>Named non-property, recorded so nothing comes to depend on it: <em>id order is not
 * creation order</em>. A UUIDv7 is monotonic per generator, not across a connection pool, so
 * {@code ORDER BY id} is right in a single-connection test and wrong in production. Ordering
 * comes from {@code family_code} (primary-keys, "A time-ordered key is not an ordering").
 */
@Component
public final class FamilyIds {

    /** The canonical 8-4-4-4-12 lower-case-or-upper-case hexadecimal form, and nothing else. */
    private static final java.util.regex.Pattern CANONICAL = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final NoArgGenerator generator;

    public FamilyIds() {
        this(Generators.timeBasedEpochGenerator());
    }

    /** Visible for the golden test, which supplies a generator over a clock it controls. */
    FamilyIds(NoArgGenerator generator) {
        this.generator = generator;
    }

    /** A fresh opaque identifier. No client supplies or chooses one (FR-002). */
    public UUID next() {
        return generator.generate();
    }

    /**
     * Reads an identifier off the wire.
     *
     * <p>Empty for anything that is not a well-formed identifier. {@code UUID.fromString} is
     * lenient — it accepts {@code "1-1-1-1-1"} — so the canonical form is checked first, and a
     * segment that is not one is an identifier no persisted family holds. FR-019 answers that
     * with the same 404 as a well-formed identifier that is simply absent, so the response is
     * never an oracle for the identifier's form (lld D-03).
     */
    public Optional<UUID> parse(String raw) {
        if (raw == null || !CANONICAL.matcher(raw).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException notAnIdentifier) {
            return Optional.empty();
        }
    }
}
