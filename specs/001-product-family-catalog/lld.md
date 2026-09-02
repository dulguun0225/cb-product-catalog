<!-- PLATFORM-14 · lld · Design · 2026-09-02 -->
---
id: lld-001-product-family-catalog
type: lld
title: Design — Product family catalog
status: draft
work_item: PLATFORM-14
spec_version: v1 (Hefesto-registered, sha256 db96798d…6383)
plan_version: v1 (Hefesto-registered, sha256 9d5f3fa8…d866)
---

# Design — Product family catalog

## 1. Purpose and scope

This design realises `001-product-family-catalog`: the product family register that every later
product definition in `CB_PRODUCT_CATALOG` will hang off. Five synchronous HTTP operations —
create, read by identifier, keyset list, retire, health — over one PostgreSQL table.

Derived from the payload copies of **spec v1** and **plan v1** (the disk copies under
`specs/001-product-family-catalog/` are byte-identical to them; both hashes match the pins in
`tasks.md`), and from **tasks v1**, T-001…T-012.

**In scope:** the `backend/` workspace, whole. One Maven module, one migration, one committed
OpenAPI 3.1 document, the build gates that hold it.

**Out of scope:** `frontend/` — this feature has no frontend workspace and creates none (plan §2),
so §6 below is empty and the FRONTEND build stage has no task here. `deploy/dev/docker-compose.yml`
is under neither workspace and is the DEPLOY stage's (plan §2, §9); no design element below writes
it. Product definitions, authentication, and any import from a predecessor catalog are the spec's
own exclusions.

The three artifacts of this stage are one approval: this document, `lld.html` beside it, and
`backend/contracts/openapi.yaml` v0.1.0. **The contract is the deliverable both builders implement
to** — the backend regenerates it from code and byte-compares, and nothing downstream may read the
backend's source instead.

## 2. Component decomposition

One workspace, `backend/`; one Maven module; root package `mn.netgroup.cb.productcatalog`.
Every module below is inside it. Dependencies are one-directional and drawn in `lld.html` panel ④;
an arrow not listed here is a layer-boundary finding at code review.

| Module | Responsibility | Depends on | Tasks |
|---|---|---|---|
| `config/` | `TimeConfig` — the one `Clock` bean. `OpenApiConfig` — document metadata, the one `OpenApiCustomizer` (D-05, D-06), the `EndpointMediaTypes` bean. `application.yml` — virtual threads, ECS structured logging, health exposure. | — | T-001, T-010, T-011 |
| `ids/` | `FamilyIds` — **the one identifier producer**, wrapping `java-uuid-generator`'s `timeBasedEpochGenerator()`; and `FamilyIds.parse(String) : Optional<UUID>`, the one reader of an identifier off the wire (D-03). | — | T-003 |
| `domain/` | `FamilyCode` (value type; its only factory rejects anything outside `[A-Z0-9]{3,20}`), `FamilyStatus`, `ProductFamily`, `ProductFamilyService` — the only caller of the repository. | `ids/`, `persistence/`, `Clock` | T-005 |
| `persistence/` | `Tx` — **the one transaction seam**, `read(Function<DSLContext,T>)` / `write(…)`; read-only intent is the method name, never an annotation; `DSLContext` is not an injectable bean. `ProductFamilyRepository` — explicit jOOQ DSL statements only. | generated jOOQ | T-004 |
| `api/` | `ProductFamilyController` — the only class that knows HTTP. `KeysetPager` — **the one class rendering a paginated query**. `CursorCodec` — **the one class encoding or decoding a cursor**. | `domain/`, `api/error/` | T-007, T-008, T-009 |
| `api/error/` | `ErrorCode` — the compile-checked catalog. `ProblemDocuments` — **the one factory that builds an error body**. `ProblemAdvice` and `ErrorPathController` — its only two callers. `CorrelationIdFilter` — mints the correlation id first in the chain. `ErrorLog` — **the one typed logging facade** (D-07, D-11). | `ErrorCode` only | T-006 |
| `db/migration/` | `V1__product_family.sql` — the one schema statement; jOOQ generates from it via `DDLDatabase`. | — | T-002 |

**Seams the platform rules require exactly one of, and where each is.** Transaction seam →
`persistence/Tx` (*SQL is reached only through the one transaction seam*). Key producer →
`ids/FamilyIds`, adopted not hand-rolled (*Exactly one application-side producer, adopted rather
than hand-rolled*). Clock → `config/TimeConfig` (*`Clock` is injected*). Pager → `api/KeysetPager`,
the single named carve-out from the `ORDER BY`-on-id ban (*A time-ordered key is not an ordering*).
Error body → `api/error/ProblemDocuments` (*One advice builds every error body*). Logging facade →
`api/error/ErrorLog` (*One typed logging facade*). Ban-list host → `BanListTest` (*The ban list is
an executable test class*).

**Seams that are dormant, each with the precondition that makes it so — not skipped.** Fan-out
helper: no in-request fan-out exists, and the ban half stays live. Plain-SQL seam: zero plain-SQL
constructs, so the ban is unconditional and there is no `@Allow.PlainSQL` scope. Cache adapter:
`caching` is installed, nothing here caches, and the `@Cacheable` family ban stays live with no
adapter behind it. Messaging adapter: plan §4 declares no asynchronous contract, so
`async-handoff` is dormant and the `@Scheduled` / `@Async` ban stays live. Number issuer: the
family code is client-supplied, not issued — see §3.

**One boundary is load-bearing:** `target/generated-sources/jooq/**` is excluded from every
ArchUnit ban, so a hand-written class placed in a generated package would escape the whole ban
list. No hand-written class is placed there.

## 3. Data model

One table, one forward migration, `V1__product_family.sql` (T-002). No other DDL exists.

| Column | Type | Rule applied |
|---|---|---|
| `id` | `uuid`, primary key, **no column `DEFAULT`** | UUIDv7 written by `FamilyIds` only. `gen_random_uuid()` banned by name in the migration; `UUID.randomUUID()` banned by name in the key-producer predicate. |
| `family_code` | `varchar(20)`, `NOT NULL` | Plain column behind its own unique index; never the primary key, never a foreign-key target, never a URL segment, never updated, stored exactly as supplied. |
| `name` | `varchar(120)`, `NOT NULL` | 1–120 characters, checked at the ingress. |
| `status` | `varchar(16)`, `NOT NULL` | `CHECK ck_product_family_status: status IN ('ACTIVE','RETIRED')`. |
| `created_at` | `timestamptz`, `NOT NULL` | Written from the injected `Clock`. **No `DEFAULT now()`, no trigger** — both are wall-clock reads in the store's language. |
| `updated_at` | `timestamptz`, `NOT NULL` | Same rule. Never derived from `Clock` on an idempotent path (D-10). |

Indexes: the primary key, and `UNIQUE INDEX ux_product_family_code (family_code)`. The unique index
is the deduplication surface for FR-018 — detection is the database's, never a pre-read, because a
pre-read races. Uniqueness is global, and that is correct because there is no tenant concept here;
`business-numbering`'s *Uniqueness is per tenant, always* is dormant for that reason.

**Key selection record** (*Rank key candidates by the surfaces the id lands on*). Surfaces the id
lands on in this repository: URL path segment; log field; JSON response member; foreign-key target
for a later feature; no export, no replication stream, no escape-hatch store. UUIDv7 behaves on all
five; a `bigint` identity is enumerable in a URL and was rejected on that surface; a key derived
from `family_code` (a hash or a UUIDv5) is **banned outright**. Cost claims checked: the classic
anti-UUID index numbers are UUIDv4 numbers and do not apply to a time-ordered key, and "a fat
primary key multiplies every secondary index" is InnoDB lore — false on PostgreSQL, where secondary
indexes point at heap tuple identifiers. Checked against the PostgreSQL 16 documentation, 2026-09-02.

**Named non-properties of the key, stated so nothing comes to depend on them.** *Id order is not
creation order*: UUIDv7 is monotonic per generator, not across a connection pool, so `ORDER BY id`
is right in a single-connection test and wrong in production. Ordering comes from `family_code`.
*The id is not a capability and not a secret*: nothing is authorised by holding one, and this
service has no authorisation at all (spec OI-005). *The id does disclose its row's creation
instant* to anyone holding it — accepted deliberately, because `createdAt` is in the response body
anyway and a family is not personal data. *The key contains no personal data and derives from none*,
so the erasure class of this table is "nothing to erase".

**The absent schema default is a named gap, not a formality.** *The schema default is the backstop,
and the banned generator is named beside it* wants the generator as the column default so operator
SQL cannot write a wrong id; native `uuidv7()` is a PostgreSQL **18** function and this deployment
is pinned to 16, so no native generator exists and the backstop is absent. Exposure: a row written
by a later migration, a repair script, or a session at a database prompt can carry a null or a v4
id, and the first symptom is a scattered index nobody attributes to this decision. Mitigation is one
test only — `FamilyIdsGoldenTest` pins the emitted layout (version nibble 7, variant bits, the
leading 48-bit timestamp increasing under a fixed clock) — and it reaches application writes, not
operator writes. Recorded as ungated in §7.

**`family_code` is a business number this system does not issue.** Live clauses: *Numbers are
immutable, never reused, never reassigned, and stored exactly as issued* — one canonical case, no
separators, not nullable, no update statement anywhere targets the column. *Parsing meaning out of a
number is banned everywhere* — no substring, prefix, regex or `LIKE` read of `family_code`, in
application source **or in query text**. *Validate at every ingress, resolve the format by lookup,
never by matching shape* — `FamilyCode.of` is called by the controller before any repository call,
and this clause is the one a client-supplied code makes load-bearing. Dormant, with the precondition
each rests on: issuance from a counter row, the transaction-handle argument, gaplessness, periods,
format versions, exhaustion, the contention ladder and legacy import all presuppose that this system
draws the number — it does not. The **check digit** clause is dormant on the *issuance* precondition,
not on the human-keyed one: a check digit is a terminal part of a format the issuer owns and cannot
be imposed on a value a client supplies; the ingress validator carries the detection burden instead.

**Migration properties.** One `CREATE TABLE` plus one unique index on an empty table: no backfill,
no rewrite, no lock hazard. The non-concurrent index build inside `CREATE TABLE` is the one
operation a migration lint would flag; the lint is not wired (plan §9) and the justification —
a new, empty table — is recorded here rather than silenced there. Rows are never deleted;
retirement is a status change and there is no purge. Reversibility is `partial`: the rows survive a
redeploy and are removable by dropping the schema. No sequence-creating DDL exists, so a data move
costs **zero** sequence-reset steps.

## 4. Interfaces

An index only. The content is in the contract files, which are the authority.

| File | Version | Operations | Requirements covered |
|---|---|---|---|
| `backend/contracts/openapi.yaml` | `0.1.0` — a new capability, first document, nothing released | 5 | FR-001…FR-011, FR-013, FR-014, FR-016…FR-023 |
| `backend/contracts/asyncapi.yaml` | — | **none** | Plan §4 declares no asynchronous contract: no topic, no queue, no outbox, no consumer, no scheduled job. The file is deliberately not created. |

Each operation carries `x-requirements` naming the FR ids it serves (D-06); their union is exactly
FR-001…FR-011, FR-013, FR-014, FR-016…FR-023 — every FR but FR-012 and FR-015. `info.version` starts at
`0.1.0`; new surface bumps it, and the same version with different content is a defect the publish
stage refuses. FR-012 and FR-015 are covered by the *absence* of surface — no operation accepts a
family code after creation, and no operation returns a body that is not either a declared success
schema or a `Problem` — so they carry no `x-requirements` entry of their own; §8 names the check for
each.

## 5. Behaviour

The four flows that cross a module or a process boundary, drawn as sequences in `lld.html` panel ③.

**F-1 — create (T-007).** ① `ProductFamilyController` binds the request record. ② It calls
`FamilyCode.of(raw)` and the name-length check **before any repository call** — malformed code →
400 `FAMILY_CODE_INVALID`, malformed name → 400 `FAMILY_NAME_INVALID`. ③ `ProductFamilyService.create`
takes an identifier from `FamilyIds.next()` and one `Instant` from the injected `Clock`, writing it
to both `created_at` and `updated_at`, and sets status `ACTIVE`. ④ One `Tx.write` inserts. ⑤ An
integrity violation on `ux_product_family_code` maps to 409 `FAMILY_CODE_DUPLICATE`; the mapping is
by constraint name, and renaming that index in a later migration silently degrades FR-018 to a 500 —
the integration test that inserts the same code twice is the only thing that catches it. ⑥ 201, with
`Location: /v1/product-families/{id}` and the family body.

**F-2 — one keyset page (T-008).** ① `limit` is validated against `[1, 100]`; any value the bounds do
not admit → 400 `LIMIT_ABOVE_MAXIMUM` with `violation` ∈ {`ABOVE_MAX`, `BELOW_MIN`, `NOT_AN_INTEGER`}
and the `min`/`max` members (D-04). It is never clamped and never silently defaulted. ② `status`
outside `{ACTIVE, RETIRED}` → 400 `STATUS_FILTER_INVALID`. ③ `CursorCodec.decode` checks the seal and
requires the carried sort specification to equal `familyCode,id`; either failure → 400
`CURSOR_INVALID`, never a best-effort seek. ④ `KeysetPager` renders
`WHERE (family_code, id) > (?, ?) ORDER BY family_code, id LIMIT limit + 1` inside one `Tx.read`;
absent `status` adds no predicate, present `status` adds one equality. ⑤ The `(limit+1)`-th row's
presence — and only that — issues `nextCursor`; otherwise it is `null`. **This page is not a
snapshot**: a family created after the first page may appear on a later one. Immunity is to skipping
and duplication only, and it holds because `(family_code, id)` is a unique total order.

**F-3 — retire, idempotently (T-009).** One `Tx.write` containing both statements — for connection
and seam hygiene, **not** because it removes an interleaving: at PostgreSQL's default READ COMMITTED
each statement takes a fresh snapshot, so the re-read sees concurrently committed rows either way.
① `UPDATE … SET status='RETIRED', updated_at=? WHERE id=? AND status='ACTIVE' RETURNING …`.
② One row → 200 with the returned values (FR-010). ③ Zero rows is a **signal, never a no-op**:
re-read and branch on `status`, not on presence — absent → 404 `FAMILY_NOT_FOUND` (FR-019);
`RETIRED` → 200 with the **persisted** `updated_at`, never one stamped from `Clock` (FR-011);
`ACTIVE` → retry the guarded update once, then 500 `INTERNAL_ERROR`. That last branch is reachable
only if a row is inserted under the same id between the two statements, and is written because
inferring "found ⇒ already retired" from presence alone would report a live family as retired.
Two concurrent retires of an `ACTIVE` row cannot both see zero rows: READ COMMITTED re-evaluates the
`WHERE` clause against the winner's version, so there is exactly one transition and both callers
return the same `updatedAt`. Isolation is **not** raised: at REPEATABLE READ the loser gets a
serialization failure instead of zero rows, turning an idempotent retire into an error the caller
must retry.

**F-4 — an unhandled failure (T-006).** ① `CorrelationIdFilter`, ordered `HIGHEST_PRECEDENCE`, mints
the correlation id, puts it in the logging context in a `try`/`finally`, and exposes it as a request
attribute. ② `ProblemAdvice` reads that attribute — it never mints its own — asks `ErrorLog` to emit
**one** ECS JSON event at ERROR carrying the id and the throwable, and asks `ProblemDocuments` for the
body. ③ The response is `500` `application/problem+json` with `code: INTERNAL_ERROR` and
`correlationId`, and **no exception message, class name or stack frame** (FR-023). ④ A failure thrown
in a servlet filter never reaches `@RestControllerAdvice`, so `ErrorPathController` covers the
`/error` dispatch and calls the same `ProblemDocuments` factory — which is why the "one place an
error body is built" rule is scoped to that factory and not to the advice class (D-07).

**State machine (spec §3).** States `ACTIVE` (initial) and `RETIRED` (terminal). `ACTIVE --retire-->
RETIRED`, no guard (FR-010). `RETIRED --retire--> RETIRED`, no guard, response unchanged (FR-011).
There is no third transition: no operation writes `status = 'ACTIVE'` after insert, so `RETIRED` is
terminal by the absence of surface, and the check constraint bounds the column to the two values.

**Asynchronous handoffs: none.** Nothing is published, consumed, scheduled or retried, so there is no
failure policy to state and `async-handoff` is dormant in whole.

## 6. Surfaces

**Omitted — the feature has no `frontend/` workspace.** The spec excludes any user interface, plan §2
creates no frontend workspace, and no task builds a file under `frontend/`. Panel ① of `lld.html`
records the same. The only surface an integrator reads is `backend/contracts/openapi.yaml`.

## 7. Non-functional enforcement

| NFR | Mechanism | Configuration value | Gate that checks it |
|---|---|---|---|
| NFR-001 read latency p95 ≤ 200 ms at 50 rps | Virtual threads (`spring.threads.virtual.enabled=true`), one indexed primary-key lookup per request, no N+1 by construction (one statement per operation) | p95 threshold 200 ms; 50 rps for 60 s | `perf/GetFamilyLatencyTest` (T-012), hosted by `./mvnw verify`, reading the p95 of `http.server.requests`. **Scope caveat:** it measures the build machine, not the dev deployment the spec scopes; read the number as a floor. |
| NFR-002 every log line parses as one JSON object | Spring Boot's own structured logging; one typed facade so no raw logger or `System.out` can emit | `logging.structured.format.console=ecs` | `config/StructuredLogFormatTest` (T-001) over captured output, plus the ArchUnit standard-streams and raw-logger bans in `BanListTest` (T-010). The config check reads the **checked-in default**, which an environment variable can still override at runtime. |
| NFR-003 zero committed credentials | No secret is committed; the cursor sealing key arrives from the environment and has no default; tests generate their own key per test class, so no key literal exists in the repository at all | 0 findings; `.env` in `.gitignore` | `NoCommittedSecretsTest` (T-012) |

**Contract and platform gates, and their hosts.** Regenerate-and-diff: `OpenApiContractDriftTest`
(T-010) boots the application, regenerates twice under varied default time zone and locale,
normalises through the one hand-owned `OpenApiNormalizer`, and fails on any byte difference against
`backend/contracts/openapi.yaml`. Runtime conformance of the health operation: a MockMvc test with
`Accept: */*` asserting status, `Content-Type` and body keys against the committed document (D-05) —
without it the drift gate proves only that the document equals itself. Traceability: the same test
asserts every operation carries a non-empty `x-requirements`, every key of the owned map resolves to
an existing `operationId`, and the union of ids equals the FR set §4 names (D-06). Error catalog:
`ErrorCatalogSnapshotTest` (T-006) diffs every `(code, status, param-names)` triple, plus the leak
test that throws a sentinel-message exception and asserts the message, class name and stack are
absent from every response body. Ban list: `BanListTest` (T-010), one ArchUnit class, generated jOOQ
packages excluded, with a meta-assertion reconciling declared entries against present rules **in both
directions**. Schema: `MigrationSchemaIT` (T-002) applies the real migration to `postgres:16.15-alpine`
via Testcontainers and asserts the unique index and the check constraint by name. Health probe:
`HealthIT` (T-011) is the probe test the autoconfigured datasource indicator requires.

**Enforcement-host census** — count the hosts, not the language features. Filled: compiler (Java 21);
architecture test (ArchUnit 1.5.0); schema/migration application (Flyway + Testcontainers);
contract generation and diff (springdoc 2.9.0 + the owned normaliser); real-dependency container
test (Testcontainers 1.21.4). **Blank, and each is a rule with no host in this feature:** compiler
plugin / annotation processor (Error Prone + NullAway not wired, plan §9); migration lint (squawk not
wired, plan §9); contract breaking-change diff (dormant by its own condition — nothing is released
and no consumer outside this build binds to the document); property or fuzz generator (the
conformance-fuzz oracle is not wired, plan §9). **ArchUnit's blast radius:** it is the single host
carrying more rules than any other here — the whole ban list, the pager scoping, the one-factory
error-body rule and the key-producer predicate die together if that class is deleted or its
predicates go stale; the meta-assertion is the only thing that notices.

**Wired nowhere — stated so silence does not read as coverage.** ① The absent `id` column default
(§3). ② The clock ban's store-language half: that no `DEFAULT now()` and no trigger writes a
timestamp is design and review, and its lint is not wired. ③ The `ORDER BY`-on-id ban has only its
ArchUnit half; the lint over committed query text, views and functions does not exist. ④ Maven has
**no native lockfile**, so there is no lockfile gate and pin discipline is exact versions in
`pom.xml` plus the Boot BOM; Maven Enforcer (`requireJavaVersion`, `banDynamicVersions`) is **not
wired** because naming a new plugin version would require a registry verification this stage may not
perform — OI-004. ⑤ Container images are tag-pinned, not digest-pinned (plan §9 declined digest
pinning). ⑥ The migrations-are-the-complete-schema drift check does not exist. ⑦ The configuration
lint for the runtime-silent ban list — a scheduler or cache manager declared in YAML rather than by
annotation — does not exist. ⑧ JaCoCo coverage floor and pitest are not wired (plan §9). ⑨ That
`If-Match` is honored, and the required-precondition rules, are dormant: this feature exposes no
client-supplied-precondition mutation, so no `ETag` and no version column exist (plan §9). ⑩ Alert
rules with fire-tests: there is no alerting stack and no CI host in this repository, so every NFR
falls back to a `mvnw verify` test. ⑪ The `-javaagent` ban is design and review only — ArchUnit reads
bytecode and cannot see a launcher flag; the `Dockerfile` entrypoint carries no agent.

## 8. Coverage

Every active requirement, once.

| Req | Design element | Check |
|---|---|---|
| FR-001 | F-1; `ProductFamilyService.create`; `openapi.yaml` `createProductFamily` | `ProductFamilyCreateReadIT` |
| FR-002 | `ids/FamilyIds.next()`, the one producer; no schema default (§3) | `FamilyIdsGoldenTest`; key-producer predicate in `BanListTest` |
| FR-003 | `config/TimeConfig` `Clock`; `created_at`/`updated_at` (§3) | wall-clock ban in `BanListTest`; `ProductFamilyServiceIT` |
| FR-004 | F-1 ⑥; `201` + `Location` in `createProductFamily` | `ProductFamilyCreateReadIT` |
| FR-005 | `getProductFamily`; `ProductFamilyRepository.findById` (`fetchOptional`) | `ProductFamilyCreateReadIT` round-trip of every field |
| FR-006 | F-2 ④ — absent `status` adds no predicate | `ProductFamilyListIT` |
| FR-007 | F-2 ④ — present `status` adds one equality predicate | `ProductFamilyListIT` |
| FR-008 | `KeysetPager` order `(family_code, id)`; `id` in no sort vocabulary (§3) | `ProductFamilyRepositoryIT`; `ORDER BY`-on-id ban in `BanListTest` |
| FR-009 | F-2 ⑤ — fetch `limit + 1`; `nextCursor` null only on the last page | `ProductFamilyListIT` pages 25 families to exhaustion |
| FR-010 | F-3 ①②; guarded `UPDATE … RETURNING` | `ProductFamilyRetireIT`; `ProductFamilyServiceIT` concurrent retire |
| FR-011 | F-3 ③ — `RETIRED` branch returns the persisted `updated_at` | `ProductFamilyRetireIT` asserts an identical body including `updatedAt` |
| FR-012 | No operation accepts a family code after creation; no statement targets the column (§3) | Contract review of `openapi.yaml`; update-target ban in `BanListTest` |
| FR-013 | No transition leaves `RETIRED` (§5); check constraint | `ProductFamilyRetireIT`; `MigrationSchemaIT` asserts the constraint by name |
| FR-014 | `GET /actuator/health`, datasource indicator on (D-05) | `HealthIT` |
| FR-015 | `ErrorCode` enum; `ProblemDocuments` the one factory | `ErrorCatalogSnapshotTest`; error-body-construction ban in `BanListTest`. **Partial — see D-08** |
| FR-016 | `domain/FamilyCode` factory, called at the ingress | `ProductFamilyCreateReadIT` 400 on a malformed code |
| FR-017 | Name-length check on the create record | `ProductFamilyCreateReadIT` 400 on an over-long name |
| FR-018 | `ux_product_family_code`; violation mapped by constraint name (F-1 ⑤) | `ProductFamilyCreateReadIT` 409 on a duplicate code |
| FR-019 | `fetchOptional` empty → 404; malformed segment → the same 404 (D-03) | `ProductFamilyCreateReadIT`, `ProductFamilyRetireIT`; a control-character and a percent-encoded segment |
| FR-020 | F-2 ① — reject, never clamp; `violation`/`min`/`max` (D-04) | `ProductFamilyListIT`: `limit=101`, `limit=0`, `limit=abc`, empty, repeated, overflow |
| FR-021 | `CursorCodec` seal check plus sort-spec equality (D-09) | `ProductFamilyListIT`: tampered cursor, cursor from a different sort spec |
| FR-022 | `status` bound to `FamilyStatus` (F-2 ②) | `ProductFamilyListIT`: `status=DRAFT` |
| FR-023 | F-4 — `ProblemAdvice` + `CorrelationIdFilter` + `ErrorLog` | Leak test in `ErrorCatalogSnapshotTest`; correlation-id-resolves-to-a-log-event test (D-07) |
| NFR-001 | §7 row 1 | `perf/GetFamilyLatencyTest` |
| NFR-002 | §7 row 2 | `config/StructuredLogFormatTest` + facade bans |
| NFR-003 | §7 row 3 | `NoCommittedSecretsTest` |
| SC-001 | `backend/contracts/openapi.yaml` as the only integration surface | `OpenApiContractDriftTest` + the health conformance test |
| SC-002 | `ux_product_family_code`; no update path (§3) | `MigrationSchemaIT`; `ProductFamilyCreateReadIT` |
| SC-003 | `ErrorCode` on every error body; clients branch on `code` alone | `ErrorCatalogSnapshotTest`. **Partial — see D-08** |
| SC-004 | `RETIRED` terminal by absence of surface (§5) | `ProductFamilyRetireIT` |

## 9. Decisions and open items

| # | Decision | Status | Ground / alternatives |
|---|---|---|---|
| D-01 | One Maven module under `backend/`, package layout as §2 | `derived` | plan §2. No skill mandates a module split; `backend-stack` decides stack, not layout. |
| D-02 | UUIDv7 from `FamilyIds`; no column default; `family_code` a plain unique-indexed column | `derived` | plan §5, §9; `primary-keys`. The absent default is the named gap in §3. |
| D-03 | The `{id}` path variable binds as `String`; `FamilyIds.parse` returns empty for anything not well-formed, and that is the same 404 `FAMILY_NOT_FOUND` as a repository miss | **NEW — proposed** | FR-019's antecedent covers an identifier no family holds, and the identifier is opaque, so a 400 that fires only on a non-UUID would be an oracle for its form. Rejected: mapping `MethodArgumentTypeMismatchException` — the same exception class carries `limit` and `status` failures and would collide with D-04, and some path segments raise `MissingPathVariableException`, which defaults to 500. Rejected: a ninth error code — that edits the approved spec. |
| D-04 | Any `limit` the declared bounds do not admit → 400 `LIMIT_ABOVE_MAXIMUM` with `violation` ∈ {`ABOVE_MAX`,`BELOW_MIN`,`NOT_AN_INTEGER`} and `min`/`max` | **NEW — proposed** | The catalog is closed and the status must be 400, so the code is fixed; the extension members are RFC 9457's own mechanism and are additive for existing clients. Without them a client library reading `LIMIT_ABOVE_MAXIMUM` for `limit=0` would halve and retry forever. The advice handles `HandlerMethodValidationException` and `MethodArgumentTypeMismatchException` **discriminated by parameter name** — the framework's own 400 body carries no `code`, which would breach FR-015 through the happy path. |
| D-05 | `springdoc.show-actuator` stays off; one owned `OpenApiCustomizer` in `OpenApiConfig` `put`s `/actuator/health` into the document, and an `EndpointMediaTypes` bean makes `application/json` the only produced type; `management.endpoint.health.show-details=never` and `show-components=never` | **NEW — proposed** | Springdoc's actuator rendering mangles the `operationId` for uniqueness and emits vendor media types, both of which move under a springdoc bump and flap the byte-identity gate. Correction found in refutation: actuator's default produced types list `application/vnd.spring-boot.actuator.v3+json` **first**, so a client sending `Accept: */*` gets the vendor type — a hand-written `application/json` contract would have been false. The bean makes it true. Rejected: listing both vendor types instead, which ships the springdoc-owned shape into the contract. |
| D-06 | The same customizer stamps `x-requirements` on every operation from one immutable `List.of` map | **NEW — proposed** | Kept because it is the only mechanism that reaches the annotation-less health path — **not** because annotations cannot express arrays; `@ExtensionProperty(parseValue = true)` renders a real array, and under `openapi_3_1` the `x-` prefix is not auto-prepended either way. A map keyed by `operationId` is fail-open, so the drift test also asserts non-empty coverage, orphan-free keys, and equality with the FR set §4 names. |
| D-07 | The correlation id is minted by a `HIGHEST_PRECEDENCE` `OncePerRequestFilter` into the logging context and a request attribute; `ProblemAdvice` reads it; `ProblemDocuments` is the one body factory and `ErrorPathController` its second caller | **NEW — proposed** | `@RestControllerAdvice` is part of dispatcher exception resolution and never sees a throwable from a servlet filter or the `/error` dispatch — minting the id in the advice would leave exactly the uncoded, id-less 500 the observability rule exists to prevent. ECS structured logging already serialises the logging context, so the filter costs one `try`/`finally`; with virtual threads there is no pool and no cross-request bleed. The test asserts the id read from a 500 body retrieves a captured log event, driven through a **throwing filter**, not only a throwing handler. |
| D-08 | The protocol-level failures the framework produces before an operation is entered — 400 on an unreadable JSON body, 405, 406, 415 — are RFC 9457 documents with the right status but **carry no `code`** | **`blocked`** | This is a defect in the approved artifacts, not a design choice: FR-015 requires every error response to carry a code, and T-006 enumerates a catalog of exactly eight that has no member for these. Every resolution edits an approved document, which this stage may not do. Proposed fix, for a re-signed spec and task list: one further member covering them. Question for the requester in OI-001. |
| D-09 | The cursor payload carries a key identifier and `CursorCodec` accepts a map of keys, so the sealing key rotates without invalidating issued cursors; structural validation (known version, sort spec equal to the request's, parseable id, code within its bound) runs independently of the seal; the key is a required property with no default, and tests generate their own per test class | **NEW — proposed** | Sealing itself is plan §9's decision and `java-backend-api`'s *Cursors are opaque, sealed, and carry their sort spec*, which this design does not reopen. What refutation added and this design adopts: without a key identifier the scheme has no rotation story, so the key would never be rotated and the property it seals for would be forfeited by construction; and the structural validation is required whether or not the payload is sealed. Recorded honestly: the cursor is **sealed, not opaque** — a client can read the sort spec and the last row's values out of it, cannot forge one, and forging one would grant no read the caller does not already have. See OI-002. |
| D-10 | Retire is `UPDATE … RETURNING` plus a conditional re-read in one `Tx.write`; the zero-row branch decides on `status`, not on presence; `updatedAt` on the idempotent path is the persisted value | **NEW — proposed** | The transaction is connection and seam hygiene, **not** interleaving removal: READ COMMITTED takes a fresh snapshot per statement, so the re-read sees committed concurrent work either way; what makes the outcome stable is that rows are never deleted and `ACTIVE → RETIRED` is terminal. Inferring "found ⇒ already retired" from presence alone reports a live family as retired if a row is inserted under the same id between the statements. Rejected: raising isolation, which converts the loser's zero rows into a serialization failure. |
| D-11 | One owned `ErrorLog` facade is the only application class that emits a log event; raw logger types, `System.out`/`System.err` and `printStackTrace` are ArchUnit-banned | **NEW — proposed** | *One typed logging facade*. This feature emits exactly one application log event, so the facade is one type with one method — the full event and metric catalogs, the fan-out context capture and the cardinality budget are dormant or deferred, each recorded in §7. |
| D-12 | Java 21 / Spring Boot 3.5.16, jOOQ 3.21.7, Flyway 13.4.0, springdoc 2.9.0, `java-uuid-generator` 5.2.0, Testcontainers 1.21.4, ArchUnit 1.5.0, Maven 3.9.16, `postgres:16.15-alpine`, `eclipse-temurin:21-jre-alpine` | `derived` | plan §9, each verified on its registry on 2026-09-02. The Java 21 and Boot 3.x pins diverge from *The pin is created at the newest supported LTS* and plan §9 records the requester as the reason. This design names **no new dependency and no new version**: this stage has no registry to verify against, so a pin it invented would be unverified by construction. |

**Open items.**

| ID | Item | Owner | Blocks |
|---|---|---|---|
| OI-001 | **D-08.** Four framework-produced failures (400 unreadable body, 405, 406, 415) have no member in the approved eight-code catalog, so they cannot carry a `code` and FR-015 is not fully true. Extend the catalog by one member — which requires re-signing `spec.md` FR-015 and `tasks.md` T-006 — or accept these four as coded-exempt and say so in the spec? | requester | FR-015 and SC-003 being fully true; nothing else |
| OI-002 | **D-09.** The cursor payload carries only the sort spec and the last row's `family_code` and `id` — all values the client just received in the response body — so the HMAC protects integrity of data that confers no authority. Is the sealing key, and the operational cost of a required secret in every environment, worth keeping at this stage? Re-signing plan §9 is the only way to drop it. | plan owner | nothing — the design implements the plan as signed |
| OI-003 | The spec's `OI-001` (family-code character set), `OI-002` (`limit` default and maximum) and `OI-003` (whether an unfiltered list includes `RETIRED`) are still open. This design and the contract encode the spec's proposed values: `[A-Z0-9]{3,20}`, 20/100, and both statuses. A different answer changes §3, §5 F-2, FR-006, FR-016, FR-020 and the contract together. | domain owner | nothing yet — the values are decided, not undecided |
| OI-004 | Maven Enforcer (`requireJavaVersion`, `banDynamicVersions`) is the off-the-shelf host for the floating-version ban and is not in the plan's dependency set. Adding it means pinning a plugin version, and this stage may not verify one on a registry. | plan owner | the floating-version ban having a host rather than a convention |
| OI-005 | The repository still has no tier-map file, so plan §7's entries are declared and unapplied (plan `PI-001`). Unchanged by this design; restated because an undeclared path routes to T1 at merge. | platform owner | tier-map completeness at merge |
