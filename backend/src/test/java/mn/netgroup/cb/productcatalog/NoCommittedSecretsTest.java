package mn.netgroup.cb.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 001:NFR-003 — "secrets absent from the repository: count of credential values committed to the
 * repository = 0, per build, over every committed file."
 *
 * <p>The metric is a count over <em>committed</em> files, so the file set comes from git itself
 * rather than from a directory walk: a walk would miss what is committed but deleted from the
 * working tree, and would include what is present but ignored.
 *
 * <p>The second half of the requirement's enforcement, per plan §8: {@code .env} is listed in
 * {@code .gitignore}, so the file that holds this deployment's real values cannot be committed by
 * accident.
 *
 * <p>What this test cannot do, stated because zero findings reads as proof: it matches shapes. A
 * credential that looks like ordinary prose passes it, and so does one committed and later removed
 * from the tip but still present in history. It is a floor, not a guarantee.
 */
class NoCommittedSecretsTest {

    /** The repository root, one level above this module. */
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    /** The credential-shaped names a value gets counted against. */
    private static final String NAMES =
            "password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|client[_-]?secret";

    /**
     * The metric counts credential <em>values</em>, so these match a literal and not an expression.
     *
     * <p>{@code String secret = keys.keys().get(keyId)} reads a key out of configuration and commits
     * nothing; {@code secret = "hunter2hunter2"} commits one. Matching the first would have made the
     * scan noisy, and a noisy scan gets its findings waved through, which is the failure a zero
     * threshold exists to prevent.
     */
    private static final List<Pattern> CREDENTIAL_SHAPES = List.of(
            // A quoted literal assigned to a credential-shaped name, in any language.
            Pattern.compile("(?i)\\b(" + NAMES + ")\\b\\s*[:=]\\s*[\"'][^\"'$\\s][^\"']{6,}[\"']"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bghp_[A-Za-z0-9]{20,}\\b"),
            // A JDBC URL carrying inline credentials.
            Pattern.compile("jdbc:[a-z]+://[^\\s/]+:[^\\s/@]+@"));

    /**
     * The same names, unquoted, in a configuration file — where a bare token <em>is</em> the value.
     * A {@code ${PLACEHOLDER}} is not one, which is the whole point of the committed configuration
     * reading from the environment.
     */
    private static final Pattern CONFIGURATION_CREDENTIAL = Pattern.compile(
            "(?i)^\\s*[\\w.\\-]*(" + NAMES + ")\\s*[:=]\\s*(?!\\$\\{)[^\\s#][^\\s#]{7,}");

    private static final List<String> CONFIGURATION_SUFFIXES =
            List.of(".yml", ".yaml", ".properties", ".env", ".conf", ".toml", ".tf", ".ini");

    /** Binary and generated files a shape match would only ever be noise in. */
    private static final List<String> SKIPPED_SUFFIXES =
            List.of(".jar", ".png", ".jpg", ".gif", ".ico", ".zip", ".class", ".woff", ".woff2");

    @Test
    void noCommittedFileCarriesACredentialShapedValue() throws Exception {
        List<String> findings = new ArrayList<>();

        for (String tracked : committedFiles()) {
            if (SKIPPED_SUFFIXES.stream().anyMatch(tracked::endsWith)) {
                continue;
            }
            Path file = ROOT.resolve(tracked);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int number = 0; number < lines.size(); number++) {
                String line = lines.get(number);
                boolean isConfiguration =
                        CONFIGURATION_SUFFIXES.stream().anyMatch(tracked::endsWith);
                for (Pattern shape : CREDENTIAL_SHAPES) {
                    if (shape.matcher(line).find()) {
                        findings.add("%s:%d — %s".formatted(tracked, number + 1, line.strip()));
                    }
                }
                if (isConfiguration && CONFIGURATION_CREDENTIAL.matcher(line).find()) {
                    findings.add("%s:%d — %s".formatted(tracked, number + 1, line.strip()));
                }
            }
        }

        assertThat(findings).as("committed credential values").isEmpty();
    }

    @Test
    void theEnvironmentFileIsIgnoredSoItCannotBeCommittedByAccident() throws Exception {
        List<String> ignoreEntries = new ArrayList<>();
        for (Path candidate : List.of(ROOT.resolve(".gitignore"), ROOT.resolve("backend/.gitignore"))) {
            if (Files.isRegularFile(candidate)) {
                ignoreEntries.addAll(Files.readAllLines(candidate, StandardCharsets.UTF_8));
            }
        }

        assertThat(ignoreEntries.stream().map(String::strip).toList())
                .as("a real .env must be unable to reach a commit")
                .contains(".env");
    }

    @Test
    void noEnvironmentFileIsCommitted() throws Exception {
        assertThat(committedFiles())
                .as("an environment file holding real values must not be tracked")
                .noneSatisfy(tracked -> assertThat(tracked).endsWith(".env"));
    }

    @Test
    void theCursorSealingKeyHasNoCommittedDefault() throws Exception {
        String configuration =
                Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        // lld D-09: the key is a required property with no default, so a missing one is a startup
        // failure rather than a silent fallback to a literal in this repository.
        assertThat(configuration)
                .as("the committed configuration must declare no cursor key material at all")
                .doesNotContain("catalog:\n  cursor:\n    keys:");
        assertThat(configuration).contains("active-key-id: ${CURSOR_ACTIVE_KEY_ID}");
    }

    /** The file set the requirement is scoped to: what git tracks. */
    private static List<String> committedFiles() throws Exception {
        // safe.directory: the build may run as a different user than the one that owns the
        // checkout — it does in this repository's containerised Maven — and git refuses to read a
        // repository it considers foreign. Reading the file list is not a trust decision.
        ProcessBuilder git = new ProcessBuilder(
                        "git", "-c", "safe.directory=" + ROOT, "ls-files")
                .directory(ROOT.toFile());
        git.redirectErrorStream(true);
        Process process = git.start();
        List<String> tracked = new ArrayList<>();
        try (BufferedReader output =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                if (!line.isBlank()) {
                    tracked.add(line);
                }
            }
        }
        assertThat(process.waitFor())
                .as("git ls-files must succeed; the requirement is scoped to committed files")
                .isZero();
        assertThat(tracked).as("the repository must have tracked files to scan").isNotEmpty();
        return tracked;
    }
}
