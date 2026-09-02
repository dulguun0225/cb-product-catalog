package mn.netgroup.cb.productcatalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ban list, as one executable test class.
 *
 * <p>java-backend-rules, "The ban list is an executable test class" and "Every ban names the check
 * that enforces it". Every entry below is either enforced by a named rule in {@link #ENFORCED} or
 * listed in {@link #DEFERRED} with a reason, and {@link #theDeclaredEntriesAndThePresentRulesAgree()}
 * reconciles the two lists <b>in both directions</b> — one direction alone lets a deferred ban be
 * described as enforced <em>or</em> lets a wired rule drop off the list, and both read as a complete
 * list.
 *
 * <p><b>Generated jOOQ packages are excluded from every rule below</b>, because generator output is
 * not hand-written code. That exclusion boundary is load-bearing: a hand-written class placed in a
 * generated package would escape the whole list. None is.
 *
 * <p><b>What this host cannot read, stated because a green run reads as coverage.</b> ArchUnit reads
 * bytecode. It cannot see the four letters {@code ORDER BY} inside a string — narrowed here because
 * plain-SQL constructs are themselves banned, so the sort is unwritable in application code, but
 * view text, function bodies and migrations stay unreached and no lint over committed query text
 * exists. It cannot see a {@code -javaagent} or an {@code --enable-preview} launcher flag. It cannot
 * see a scheduler or cache manager declared in YAML rather than by annotation. Each of those is
 * recorded in lld §7 as wired nowhere.
 *
 * <p>ArchUnit is the single host carrying more rules than any other in this feature. If this class
 * is deleted or its predicates go stale, the whole list dies together, and the meta-assertion below
 * is the only thing that would notice.
 */
class BanListTest {

    /** Everything under the root package except the generated jOOQ tree. */
    private static final JavaClasses APPLICATION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("/generated/"))
            .importPackages("mn.netgroup.cb.productcatalog");

    private static final String IDS = "mn.netgroup.cb.productcatalog.ids.FamilyIds";
    private static final String TX = "mn.netgroup.cb.productcatalog.persistence.Tx";
    private static final String REPOSITORY =
            "mn.netgroup.cb.productcatalog.persistence.ProductFamilyRepository";
    private static final String PROBLEM_DOCUMENTS =
            "mn.netgroup.cb.productcatalog.api.error.ProblemDocuments";
    private static final String ERROR_LOG = "mn.netgroup.cb.productcatalog.api.error.ErrorLog";

    /** Every ban, and the rule that enforces it. */
    private static final Map<String, ArchRule> ENFORCED = enforced();

    /**
     * Every ban with no rule here, and why.
     *
     * <p>A reason, not a placeholder: each of these is a rule the platform states and this host
     * cannot read, so listing it is the difference between an honest list and a short one.
     */
    private static final Map<String, String> DEFERRED = Map.of(
            "--enable-preview on javac or the java launcher",
                    "ArchUnit reads bytecode and cannot see compiler or launcher flags. Preview code"
                            + " fails to compile without the flag, so the build fails closed; the grep"
                            + " over build files that java-backend-rules asks for is not wired (lld §7).",
            "-javaagent bytecode weaving",
                    "Same host limitation. The Dockerfile entrypoint carries no agent and no agent JAR"
                            + " is in the image, but that is design and review, not this test"
                            + " (java-backend-observability, 'The weaving-agent ban'; lld §7 item 11).",
            "ORDER BY on an id column inside query text, a view, a function or a migration",
                    "This host reads the jOOQ builder half only. The lint over committed query text"
                            + " that primary-keys asks for does not exist here (lld §7 item 3). The"
                            + " exposure is narrowed by the plain-SQL ban, which is enforced below.",
            "A scheduler, cache manager or aspect declared in configuration rather than by annotation",
                    "No bytecode rule reads a YAML property key. The configuration lint is not wired"
                            + " (lld §7 item 7).",
            "A domain type reaching a log call",
                    "java-backend-observability and llm-default-traps both say this must be Error"
                            + " Prone, never ArchUnit: a logger's erased Object... signature hides the"
                            + " argument's static type, so an ArchUnit rule here would report green"
                            + " while protecting nothing. Error Prone is not wired (plan §9). The"
                            + " one-facade rule below is what narrows it: ErrorLog takes a UUID and a"
                            + " Throwable and no domain type can reach it.");

    private static Map<String, ArchRule> enforced() {
        Map<String, ArchRule> rules = new LinkedHashMap<>();

        rules.put(
                "An injectable DSLContext",
                noClasses()
                        .that(areNot(TX))
                        .should(holdADslContext())
                        .because(
                                "SQL is reached only through the one transaction seam; an injected"
                                        + " DSLContext used outside a block runs in autocommit and"
                                        + " commits each statement on its own, invisibly. Receiving one"
                                        + " as the seam's lambda parameter is the compliant shape and is"
                                        + " deliberately not banned; holding one is the banned shape.")
                        .allowEmptyShould(true));

        rules.put(
                "jOOQ attached-record CRUD",
                noClasses()
                        .should()
                        .callMethodWhere(targetIsOneOf(
                                "org.jooq.UpdatableRecord",
                                List.of("store", "insert", "update", "delete", "refresh", "changed",
                                        "touched", "modified")))
                        .because("attached-record writes pick columns from in-memory state that never"
                                + " appears in query text")
                        .allowEmptyShould(true));

        rules.put(
                "fetchOne and fetchAny",
                noClasses()
                        .should()
                        .callMethodWhere(nameIsOneOf("fetchOne", "fetchAny"))
                        .because("both hide result cardinality: fetchOne tolerates zero rows silently"
                                + " and fetchAny returns an arbitrary row")
                        .allowEmptyShould(true));

        rules.put(
                "Plain-SQL String constructs",
                noClasses()
                        .should()
                        .callMethodWhere(plainSql())
                        .because("each splices a raw string into the query tree, defeating compile-time"
                                + " type checking and reopening the injection surface")
                        .allowEmptyShould(true));

        rules.put(
                "A second SQL path beside jOOQ",
                noClasses()
                        .should()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                        .orShould()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName(
                                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
                        .orShould()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                        .orShould()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName("org.springframework.jdbc.core.simple.SimpleJdbcInsert")
                        .because("a second persistence idiom beside jOOQ makes every later query answer"
                                + " which one it is in")
                        .allowEmptyShould(true));

        rules.put(
                "A wall-clock read in domain or persistence code",
                noClasses()
                        .that()
                        .resideInAnyPackage(
                                "mn.netgroup.cb.productcatalog.domain..",
                                "mn.netgroup.cb.productcatalog.persistence..")
                        .should()
                        .callMethodWhere(wallClock())
                        .because("Clock is injected; the store-language half of this ban is discharged"
                                + " by the migration carrying no DEFAULT now() and no trigger")
                        .allowEmptyShould(true));

        rules.put(
                "ORDER BY touching the identifier outside the one pager seam",
                noClasses()
                        .that(areNot(REPOSITORY))
                        .should()
                        .callMethodWhere(nameIsOneOf("orderBy"))
                        .because("primary-keys grants exactly one carve-out for a keyset pager's final"
                                + " tiebreak, and ProductFamilyRepository#findPage is the single named"
                                + " seam it is scoped to. KeysetPager renders the page; this method"
                                + " renders the ordered statement.")
                        .allowEmptyShould(true));

        rules.put(
                "An offset-emitting paging target",
                noClasses()
                        .should()
                        .callMethodWhere(nameIsOneOf("offset", "addOffset", "limitOffset"))
                        .because("offset paging silently skips and duplicates rows under concurrent"
                                + " insert. Every offset-emitting target is named, not just one.")
                        .allowEmptyShould(true));

        rules.put(
                "Field and setter injection",
                noClasses()
                        .should()
                        .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                        .because("constructor injection only")
                        .allowEmptyShould(true));

        rules.put("@Transactional", bansAnnotation(
                "org.springframework.transaction.annotation.Transactional",
                "transactions are explicit visible blocks reached through the one seam"));
        rules.put("@Scheduled", bansAnnotation(
                "org.springframework.scheduling.annotation.Scheduled",
                "this feature declares no asynchronous contract; async-handoff is dormant and the ban"
                        + " stays live"));
        rules.put("@Async", bansAnnotation(
                "org.springframework.scheduling.annotation.Async",
                "same ground as @Scheduled"));
        rules.put("@Cacheable and its family", bansAnnotation(
                "org.springframework.cache.annotation.Cacheable",
                "nothing here caches; the caching skill is installed, no adapter sits behind this ban,"
                        + " and the ban stays live"));
        rules.put("@CachePut", bansAnnotation("org.springframework.cache.annotation.CachePut", "same"));
        rules.put("@CacheEvict", bansAnnotation("org.springframework.cache.annotation.CacheEvict", "same"));
        rules.put("@PatchMapping", bansAnnotation(
                "org.springframework.web.bind.annotation.PatchMapping",
                "JSON Merge Patch reads a null member as 'delete this field'"));

        rules.put(
                "A version-4 UUID generator outside the one producer",
                noClasses()
                        .that(areNot(IDS))
                        .should()
                        .callMethodWhere(targetIsOneOf("java.util.UUID", List.of("randomUUID")))
                        .because("primary-keys names the banned generator beside the one producer;"
                                + " UUID.randomUUID() is the scatter the time-ordered choice avoids")
                        .allowEmptyShould(true));

        rules.put(
                "An error body built outside the one factory",
                noClasses()
                        .that(areNot(PROBLEM_DOCUMENTS))
                        .should()
                        .callMethodWhere(targetIsOneOf(
                                "org.springframework.http.ProblemDetail",
                                List.of("forStatus", "forStatusAndDetail")))
                        .because("one factory builds every error body. It is scoped to the factory and"
                                + " not to the advice because a failure raised in a servlet filter never"
                                + " reaches @RestControllerAdvice (lld D-07).")
                        .allowEmptyShould(true));

        rules.put(
                "A raw logger outside the one facade",
                noClasses()
                        .that(areNot(ERROR_LOG))
                        .should()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName("org.slf4j.Logger")
                        .because("one typed logging facade; a raw logger takes types the facade refuses")
                        .allowEmptyShould(true));

        rules.put(
                "System.out, System.err and printStackTrace",
                com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
                        .allowEmptyShould(true));

        rules.put(
                "java.util.logging",
                com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING
                        .allowEmptyShould(true));

        rules.put(
                "Reactive WebFlux as a second concurrency model",
                noClasses()
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("org.springframework.web.reactive..", "reactor.core..")
                        .because("one concurrency model is blocking thread-per-request on virtual"
                                + " threads; a second makes every later class answer which it is in")
                        .allowEmptyShould(true));

        rules.put(
                "JPA, Hibernate and Spring Data",
                noClasses()
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("jakarta.persistence..", "org.hibernate..",
                                "org.springframework.data..")
                        .because("dirty checking, name-derived queries and reflective row mapping are"
                                + " all runtime-silent")
                        .allowEmptyShould(true));

        rules.put(
                "A fixed-size executor for request work",
                noClasses()
                        .should()
                        .callMethodWhere(targetIsOneOf(
                                "java.util.concurrent.Executors",
                                List.of("newFixedThreadPool", "newCachedThreadPool",
                                        "newSingleThreadExecutor", "newWorkStealingPool")))
                        .because("virtual threads are never pooled; a fixed pool reintroduces the"
                                + " thread-pool exhaustion they remove")
                        .allowEmptyShould(true));

        rules.put(
                "An UPDATE statement rendered outside the one repository",
                noClasses()
                        .should()
                        .callMethodWhere(familyCodeUpdate())
                        .because("FR-012: the service exposes no operation that changes a persisted"
                                + " family code. What bytecode can carry is that only the one"
                                + " repository renders an UPDATE at all; that its set(...) never"
                                + " targets FAMILY_CODE is design and the repository's own test.")
                        .allowEmptyShould(true));

        return rules;
    }

    @Test
    void everyBanHolds() {
        ENFORCED.forEach((ban, rule) -> rule.check(APPLICATION));
    }

    @Test
    void theDeclaredEntriesAndThePresentRulesAgree() {
        // Both directions. A declared-enforced ban must name a rule that exists, and a rule that
        // exists must not be undeclared. One direction alone reads as a complete list either way.
        assertThat(ENFORCED.keySet())
                .as("a ban cannot be both enforced and deferred")
                .doesNotContainAnyElementsOf(DEFERRED.keySet());
        assertThat(ENFORCED.values()).as("every declared-enforced ban names a rule").doesNotContainNull();
        assertThat(DEFERRED.values())
                .as("every deferred ban carries a reason, not a placeholder")
                .allSatisfy(reason -> assertThat(reason).hasSizeGreaterThan(40));
        assertThat(ENFORCED).as("the ban list must not be empty").isNotEmpty();

        // The other direction, and the one that is easy to leave out: a rule wired somewhere in
        // this class but not declared in ENFORCED would run and yet not appear on the list, and the
        // list would read as complete. ENFORCED is the only place an ArchRule may live here.
        List<String> ruleHoldersOutsideTheMap = java.util.Arrays.stream(BanListTest.class.getDeclaredFields())
                .filter(field -> ArchRule.class.isAssignableFrom(field.getType()))
                .map(java.lang.reflect.Field::getName)
                .toList();
        assertThat(ruleHoldersOutsideTheMap)
                .as("every rule must be declared in ENFORCED, so the list cannot be short")
                .isEmpty();
    }

    @Test
    void noHandWrittenClassHidesInAGeneratedPackage() {
        // The exclusion boundary above is load-bearing: a hand-written class placed in a generated
        // package would escape every rule in this class.
        java.nio.file.Path generated = java.nio.file.Path.of(
                "src/main/java/mn/netgroup/cb/productcatalog/generated");

        assertThat(java.nio.file.Files.exists(generated))
                .as("no hand-written source may live in the generated package")
                .isFalse();
    }

    /**
     * Holding a {@code DSLContext} — as a field or as a constructor parameter.
     *
     * <p>The distinction is the whole rule. {@code ProductFamilyRepository} <em>receives</em> a
     * context as a method parameter, which is exactly the shape the transaction seam mandates; a
     * rule banning every dependency would ban the compliant shape along with the banned one and get
     * relaxed. What must be unwritable is holding one, because a held context is one usable outside
     * a transaction block.
     */
    private static com.tngtech.archunit.lang.ArchCondition<JavaClass> holdADslContext() {
        return new com.tngtech.archunit.lang.ArchCondition<>("hold a DSLContext as a field or a constructor parameter") {
            @Override
            public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                item.getFields().stream()
                        .filter(field -> field.getRawType().getFullName().equals("org.jooq.DSLContext"))
                        .forEach(field -> events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                                field, "%s holds a DSLContext field".formatted(item.getFullName()))));
                item.getConstructors().stream()
                        .filter(constructor -> constructor.getRawParameterTypes().stream()
                                .anyMatch(type -> type.getFullName().equals("org.jooq.DSLContext")))
                        .forEach(constructor -> events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                                constructor,
                                "%s takes a DSLContext in its constructor".formatted(item.getFullName()))));
            }
        };
    }

    private static ArchRule bansAnnotation(String annotation, String because) {
        // Meta-annotated and type-level forms too, not only a direct method-level annotation: the
        // framework resolves a repo-defined annotation that is itself annotated with a banned one
        // and behaves identically, so a rule matching only the direct form is a rule about spellings.
        return noClasses()
                .should()
                .beMetaAnnotatedWith(annotation)
                .orShould()
                .beAnnotatedWith(annotation)
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(annotation)
                .because(because)
                .allowEmptyShould(true);
    }

    private static DescribedPredicate<JavaClass> areNot(String fullyQualifiedName) {
        return new DescribedPredicate<>("are not " + fullyQualifiedName) {
            @Override
            public boolean test(JavaClass candidate) {
                return !candidate.getFullName().equals(fullyQualifiedName)
                        && !candidate.getFullName().startsWith(fullyQualifiedName + "$");
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> nameIsOneOf(String... names) {
        List<String> banned = List.of(names);
        return new DescribedPredicate<>("the target is named one of " + banned) {
            @Override
            public boolean test(JavaMethodCall call) {
                return banned.contains(call.getTarget().getName());
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> targetIsOneOf(String owner, List<String> names) {
        return new DescribedPredicate<>("the target is %s.%s".formatted(owner, names)) {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isAssignableTo(owner) && names.contains(call.getTarget().getName());
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> plainSql() {
        List<String> banned = List.of("sql", "query", "resultQuery");
        return new DescribedPredicate<>("the target is a plain-SQL construct on org.jooq.impl.DSL") {
            @Override
            public boolean test(JavaMethodCall call) {
                String owner = call.getTargetOwner().getFullName();
                String name = call.getTarget().getName();
                if (!owner.equals("org.jooq.impl.DSL") && !owner.equals("org.jooq.DSLContext")) {
                    return false;
                }
                if (banned.contains(name)) {
                    return true;
                }
                // field(String), condition(String), table(String), fetch(String) — the String
                // overloads only; the type-safe ones are how every statement here is written.
                boolean overloadable = List.of("field", "condition", "table", "fetch").contains(name);
                return overloadable
                        && call.getTarget().getParameterTypes().size() == 1
                        && call.getTarget().getParameterTypes().get(0).getName().equals("java.lang.String");
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> wallClock() {
        Map<String, String> banned = Map.of(
                "java.time.Instant", "now",
                "java.time.LocalDate", "now",
                "java.time.LocalDateTime", "now",
                "java.time.ZonedDateTime", "now",
                "java.lang.System", "currentTimeMillis");
        return new DescribedPredicate<>("the target is a wall-clock read") {
            @Override
            public boolean test(JavaMethodCall call) {
                String owner = call.getTargetOwner().getFullName();
                String name = call.getTarget().getName();
                if (owner.equals("java.util.Date") && name.equals("<init>")) {
                    return true;
                }
                // Instant.now(Clock) is the injected clock read and is not a wall-clock read.
                return banned.getOrDefault(owner, "").equals(name)
                        && call.getTarget().getParameterTypes().isEmpty();
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> familyCodeUpdate() {
        return new DescribedPredicate<>("an UPDATE is rendered outside ProductFamilyRepository") {
            @Override
            public boolean test(JavaMethodCall call) {
                // Which column an update's set(...) targets cannot be read from bytecode. What can
                // be read is that no class renders an UPDATE except the one repository.
                return call.getTarget().getName().equals("update")
                        && call.getTargetOwner().isAssignableTo("org.jooq.DSLContext")
                        && !call.getOriginOwner().getFullName().equals(REPOSITORY);
            }
        };
    }
}
