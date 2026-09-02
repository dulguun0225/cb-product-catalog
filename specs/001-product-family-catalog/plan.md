# Plan — Product family catalog

| | |
|---|---|
| **Feature** | `001-product-family-catalog` |
| **Spec** | `spec.md` — hash `db96798df5684fbe` |
| **Authored** | `2026-09-02` |
| **Signer** | plan gate — the team's engineer |
| **Assertion** | *This is a sound approach to that problem.* |
| **Advisory tier** | `T1`, rule `2` — plan-time run, **not binding**. Rule 2 fires because this change creates the repository's tier-map file and its first Flyway migration and its first committed API contract; the feature's application code is T2 on its own. The binding tier is computed on the final diff at merge; if it comes out higher, this plan must be re-signed. |

## 1. Summary

One new Spring Boot Web MVC service in a single Maven module under `backend/`, exposing five
synchronous HTTP operations over one PostgreSQL table, with the contract committed as an OpenAPI
3.1 document and a build gate that fails on drift from it. Two decisions shape everything else.
First, **the opaque identifier is a UUIDv7 produced in application code**, because PostgreSQL 16
has no native `uuidv7()` function and the corpus-default `gen_random_uuid()` is banned by name —
so the key column carries no schema default and one owned producer is the only thing that makes
one. Second, **the repository's own `mvnw verify` is the only gate host that exists**: there is
no CI in this repository, so every check this plan promises is a Maven-bound test, and the ones
that cannot be are recorded in §9 as ungated rather than described as enforced.

## 2. Architecture

**Workspaces.** The repository layout is fixed: `backend/` holds the backend service, `frontend/`
holds the microfrontend. **This feature has no `frontend/` workspace and creates none** — the
spec excludes any user interface, so the FRONTEND stage has no tasks in this feature and no file
below is under `frontend/`.

Everything this feature builds lives under **`backend/`**, one Maven module:

```
backend/
  pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/          build: pinned Java, Boot, jOOQ, Flyway, gates
  Dockerfile                                      packaging: JRE base + target/*.jar
  contracts/openapi.yaml                          the committed contract, byte-compared by a test
  src/main/resources/db/migration/V1__*.sql       Flyway migration — the only schema statement
  src/main/java/mn/netgroup/cb/productcatalog/
    ProductCatalogApplication.java                entry point
    config/                                       Clock bean, virtual threads, structured logs, OpenAPI metadata
    ids/FamilyIds.java                            the one UUIDv7 producer
    domain/                                       ProductFamily, FamilyCode, FamilyStatus, ProductFamilyService
    persistence/Tx.java                           the one lambda-scoped transaction seam
    persistence/ProductFamilyRepository.java      explicit jOOQ DSL statements only
    api/                                          controllers, request/response records, KeysetPager, CursorCodec
    api/error/                                    ErrorCode enum + the one @RestControllerAdvice
  target/generated-sources/jooq/                  generated, not committed
  src/test/java/...                               unit, Testcontainers integration, contract, guardrail tests
```

**Component relations.** `api/` is the only package that knows HTTP; it calls
`ProductFamilyService`, which is the only caller of `ProductFamilyRepository`, which reaches SQL
only inside a `Tx.read`/`Tx.write` lambda. `FamilyIds` is the only caller of a UUID generator.
`api/error/` is the only place an error body is built. Nothing already exists — the repository
holds a `README.md` and nothing else.

**One path outside both workspaces.** `deploy/dev/docker-compose.yml` is deployment packaging,
not a build workspace: the DEPLOY stage owns it (its `deploy` skill creates and commits the
compose project at `<COMPOSE_PATH>/docker-compose.yml`, and `CONN_*_COMPOSE_PATH` is `deploy/dev`).
This plan decides its content in §9 and declares its path in §7, and **no task builds it**,
because a task under neither workspace has no build stage to run it.

## 3. Synchronous contracts

Base path `/v1`. Media type `application/json`; every error body `application/problem+json`.

| Operation | Method & path | Auth | Request | Responses | Errors | Idempotent |
|---|---|---|---|---|---|---|
| Create family | `POST /v1/product-families` | none (spec §1 excludes it) | `{ familyCode, name }` | `201` + `Location` + family body | `400 FAMILY_CODE_INVALID` / `FAMILY_NAME_INVALID`, `409 FAMILY_CODE_DUPLICATE`, `500 INTERNAL_ERROR` | No — a repeat with the same `familyCode` is a `409`. No client-supplied idempotency key: the family code's unique index is the deduplication surface, and the spec asks for a `409` rather than a replayed `201`. |
| Get family | `GET /v1/product-families/{id}` | none | — | `200` family body | `404 FAMILY_NOT_FOUND`, `500` | Yes — read-only |
| List families | `GET /v1/product-families?status=&limit=&cursor=` | none | — | `200 { items, nextCursor }` | `400 LIMIT_ABOVE_MAXIMUM` / `CURSOR_INVALID` / `STATUS_FILTER_INVALID`, `500` | Yes — read-only |
| Retire family | `POST /v1/product-families/{id}/retire` | none | empty body | `200` family body | `404 FAMILY_NOT_FOUND`, `500` | **Yes** — a retire on a `RETIRED` family returns it unchanged with `200` |
| Health | `GET /actuator/health` | none | — | `200 {"status":"UP"}` | `503` when a dependency is down | Yes — read-only |

`limit` default 20, declared maximum 100 (spec `OI-002`). `id` is the opaque UUIDv7; the family
code never appears as a path segment. Instants serialise as RFC 3339 UTC with `Z`, field names
`createdAt` / `updatedAt`. `PATCH` appears nowhere.

**The artifact that carries this table** is `backend/contracts/openapi.yaml` — one committed
OpenAPI 3.1 document, generated from the running application, normalised, and byte-compared by a
test in `mvnw verify`. Phase 4 produces it; the diff is the contract review.

## 4. Asynchronous contracts

**None.** This feature produces and consumes no messages: no topic, no queue, no outbox, no
consumer, no scheduled job. Retirement is a synchronous state change with no notification.

## 5. Data and storage

One new table, owned by this service, in its own PostgreSQL 16 database.

```sql
product_family (
  id           uuid        primary key,          -- UUIDv7, assigned in application code
  family_code  varchar(20) not null unique,      -- canonical form, exactly as supplied
  name         varchar(120) not null,
  status       varchar(16) not null,             -- ACTIVE | RETIRED, checked
  created_at   timestamptz not null,
  updated_at   timestamptz not null
)
```

- **The key column carries no schema default, and that is a named gap.** `primary-keys` wants the
  generator as the column default so operator SQL cannot write a wrong id; native `uuidv7()` is a
  PostgreSQL 16 function this engine does not have, and `gen_random_uuid()` is banned by name.
  The backstop is therefore absent: a row inserted outside the application can carry a v4 id.
- **`family_code` is not a key and not a URL identifier** — a plain column behind its own unique
  index, per `primary-keys`. No `UPDATE` statement in the repository targets it.
- **Ordering never uses `id` as a leading sort.** The list query orders by `family_code`, with
  `id` appended as the final tiebreak inside the one pager class. Identifier order is an explicit
  non-property of the contract.
- **Retention:** rows are never deleted. Retirement is a status change; there is no purge.
- **Reversibility — `partial`. This feature writes state that redeploying does not undo:** the
  table and its rows live in the PostgreSQL volume and survive a container replacement. It is not
  `irreversible` — the writes are internal, have no external side effect, and can be removed by
  dropping the schema, so the service stays on the automatic deploy path.
- **Migration:** one forward migration, `V1__product_family.sql`, `CREATE TABLE` plus one unique
  index on an empty table. No backfill, no rewrite, no lock hazard.

## 6. Requirements traceability

| Requirement | Design element that satisfies it | Notes |
|---|---|---|
| FR-001 | §3 `POST /v1/product-families` → `ProductFamilyService.create` → `ProductFamilyRepository.insert` | status set to `ACTIVE` at insert |
| FR-002 | `ids/FamilyIds` (§2), called only by `ProductFamilyService.create` | §5 records the absent schema default as a gap |
| FR-003 | injected `Clock` bean (§9), `created_at` / `updated_at` in §5 | domain code makes no wall-clock call |
| FR-004 | §3 create row — `201` + `Location: /v1/product-families/{id}` | |
| FR-005 | §3 `GET /v1/product-families/{id}` → `ProductFamilyRepository.findById` | |
| FR-006 | §3 list row — absent `status` parameter means no `WHERE` clause on status | spec `OI-003` may overturn this |
| FR-007 | §3 list row — `status` parameter becomes one equality predicate | |
| FR-008 | `api/KeysetPager` ordering `(family_code, id)` (§5) | `id` is the final tiebreak only |
| FR-009 | `api/CursorCodec` + pager: fetch `limit + 1`, emit a cursor only when the extra row exists | |
| FR-010 | §3 retire row → `ProductFamilyService.retire` → guarded `UPDATE ... WHERE status = 'ACTIVE'` | |
| FR-011 | `ProductFamilyService.retire` re-reads and returns the row when the update affects no row and the row is already `RETIRED` | |
| FR-012 | §5 — no repository statement targets `family_code`; no contract operation accepts it after creation | ArchUnit guards the first half |
| FR-013 | no operation writes `status = 'ACTIVE'` after insert; `RETIRED` is terminal in §5's check constraint direction | |
| FR-014 | Spring Boot Actuator health at `GET /actuator/health`, with the datasource indicator on | also the compose healthcheck (§9) |
| FR-015 | `api/error/ErrorCode` enum + the one `@RestControllerAdvice` | catalog snapshot diffed (§9) |
| FR-016 | `domain/FamilyCode` value type rejecting non-conforming input, mapped to `FAMILY_CODE_INVALID` | |
| FR-017 | bean validation on the create request record, mapped to `FAMILY_NAME_INVALID` | |
| FR-018 | unique index on `family_code` (§5); the integrity violation maps to `FAMILY_CODE_DUPLICATE` | detected at the database, not by a pre-read |
| FR-019 | `fetchOptional` in the repository; empty maps to `FAMILY_NOT_FOUND` | |
| FR-020 | `limit` bound check in the list controller, rejecting rather than clamping | |
| FR-021 | `api/CursorCodec` HMAC check plus sort-spec comparison | |
| FR-022 | `status` parameter bound to the enum, failure mapped to `STATUS_FILTER_INVALID` | |
| FR-023 | the advice's unknown-throwable branch: code, status and correlation id only | no message, class name or stack on the wire |
| NFR-001 | §8 `test` row — `backend/src/test/java/.../perf/GetFamilyLatencyTest.java` | |
| NFR-002 | §8 `test` row — Boot structured logging (§9), asserted by a log-format test | |
| NFR-003 | §8 `test` row — a committed-secret scan test plus `.env` in `.gitignore` | |

## 7. Tier-map entries for new paths

```yaml
services:
  cb-product-catalog:
    reversibility: partial      # rows survive a redeploy; removable by dropping the schema
    blast_radius: internal      # dev deployment only, no external consumer yet

paths:
  - glob: "backend/pom.xml"
    tier: 1
    service: cb-product-catalog
  - glob: "backend/src/main/resources/db/migration/**"
    tier: 1
    service: cb-product-catalog
  - glob: "backend/contracts/**"
    tier: 1
    service: cb-product-catalog
  - glob: "backend/src/main/java/**"
    tier: 2
    service: cb-product-catalog
  - glob: "backend/Dockerfile"
    tier: 2
    service: cb-product-catalog
  - glob: "deploy/dev/**"
    tier: 2
    service: cb-product-catalog
  - glob: "backend/src/test/**"
    tier: 3
    service: cb-product-catalog
```

`backend/pom.xml`, the migration folder and the contract folder are T1 because they carry the
gates, the schema and the API surface respectively. **These entries are declared here and not
yet applied: this repository has no tier-map file.** Creating it is itself a T1 change reviewed
by the platform owner — see *Open items*.

## 8. Non-functional enforcement

| NFR | Enforcement | Metric / test | Proposed value | Set by |
|---|---|---|---|---|
| NFR-001 | `test` | `backend/src/test/java/mn/netgroup/cb/productcatalog/perf/GetFamilyLatencyTest.java` — 50 rps for 60 s against the running service, p95 of `http.server.requests` | p95 ≤ 200 ms | this plan |
| NFR-002 | `test` | `backend/src/test/java/mn/netgroup/cb/productcatalog/config/StructuredLogFormatTest.java` — every captured line parses as one JSON object | 100% | this plan |
| NFR-003 | `test` | `backend/src/test/java/mn/netgroup/cb/productcatalog/NoCommittedSecretsTest.java` — scans committed files for credential-shaped values and asserts `.env` is ignored | 0 findings | this plan |

**No `canary` row, and the reason is that the route does not exist.** The dev target is
`docker compose` on one host with no progressive-rollout mechanism, so a canary threshold would
have nothing to abort a deploy. All three NFRs fall back to `test`, hosted by `mvnw verify`.

## 9. Decision trace

Every dependency named below was verified on Maven Central on **2026-09-02** with
`curl -s https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml`, and the two
container images on Docker Hub the same day, per `llm-default-traps` *Registry verification
before adoption*. No version below is a range, a `LATEST` or resolved at build time.

| Choice | Decision | Source |
|---|---|---|
| Language and runtime | Java **21** LTS, `maven.compiler.release=21`. The newest LTS on the vendor's page is 25, which the record would pin. | Diverges from `java-backend-rules` *The pin is created at the newest supported LTS* — the requester fixed 21 for this pipeline test |
| Web framework | Spring Boot **3.5.16** (newest 3.x GA; 4.1.1 is the newest GA line and 4.2.0-M1 a milestone), servlet Web MVC. WebFlux rejected: a second concurrency model in one repository. | `java-backend-rules` *Java and Spring Boot Web MVC, with WebFlux banned as a paradigm*; the 3.x line Diverges from *The pin is created at the newest supported LTS* — the requester fixed the 3.x line |
| Persistence | jOOQ **3.21.7** against PostgreSQL **16**, explicit DSL statements, `fetchSingle`/`fetchOptional`, no plain-SQL strings, `DSLContext` not injectable. JPA, Hibernate, Spring Data JPA/JDBC and the `JdbcTemplate` family rejected: dirty checking, name-derived queries and reflective row mapping are all runtime-silent. | `java-backend-rules` *jOOQ against PostgreSQL, with JPA and Spring Data banned*, *Fetch with `fetchSingle` or `fetchOptional`*, *Plain-SQL `String` constructs are banned*, *SQL is reached only through the one transaction seam* |
| Migrations | Flyway **13.4.0** (`flyway-core`, `flyway-database-postgresql`), one committed SQL migration, applied to real PostgreSQL in tests. | `java-backend-rules` *Schema changes are committed Flyway migrations* |
| jOOQ code generation input | `org.jooq:jooq-meta-extensions` **3.21.7** `DDLDatabase`, parsing the committed migration files — a pure function of committed bytes, no Docker in the generate phase. `testcontainers-jooq-codegen-maven-plugin` rejected: last release 0.0.4 on 2024-04-25, unmaintained against jOOQ 3.21. Live database rejected outright. | Diverges from `java-backend-rules` *jOOQ classes are generated from the committed migrations*, which mandates a throwaway container; the input set is identical |
| Opaque identifier | `uuid` column, UUIDv7 produced by one owned `FamilyIds` type wrapping `com.fasterxml.uuid:java-uuid-generator` **5.2.0**, with a golden test pinning the emitted layout. `gen_random_uuid()` and `UUID.randomUUID()` banned by name; hand-rolling the bit layout rejected — the answer is buy. bigint identity rejected: enumerable in a URL. | `primary-keys` *The schema default is the backstop, and the banned generator is named beside it*, *Exactly one application-side producer, adopted rather than hand-rolled*; the absent column default is the gap §5 names |
| Family code as an identifier | A plain unique-indexed column: never the primary key, never a URL segment, never parsed for meaning, never updated, stored exactly as supplied. | `primary-keys` *The opaque key and the human-facing number are two identifiers*; `business-numbering` *Numbers are immutable, never reused, never reassigned, and stored exactly as issued*, *Parsing meaning out of a number is banned everywhere* |
| Number-issuing machinery | None. The family code is client-supplied, so counter rows, gaplessness, periods, format versions, check digits and exhaustion are dormant by that skill's own condition — not skipped. | `business-numbering` *Issue from a counter row inside the caller's transaction* — its precondition is absent here |
| List paging | Keyset over `(family_code, id)`; `limit` default 20, hard maximum 100, above-maximum rejected rather than clamped; cursor HMAC-sealed and carrying its sort spec; body `{ items, nextCursor }`; one owned `KeysetPager`. `offset`, `page` and `pageNumber` rejected: they skip and duplicate rows under concurrent insert. | `java-backend-api` *Keyset pagination only*, *No offset parameter in the contract*, *`limit` has a default and a hard maximum*, *Cursors are opaque, sealed, and carry their sort spec*, *The list response shape*; `primary-keys` *A time-ordered key is not an ordering* (pager carve-out) |
| Error shape | RFC 9457 problem documents, built only in one `@RestControllerAdvice`, each carrying a code from one compile-checked `ErrorCode` enum snapshotted and diffed by a test. Free-form error JSON and per-controller error bodies rejected. | `java-backend-api` *Every error response is a problem document*, *One advice builds every error body*, *Every error carries a code from one compile-checked catalog*, *The catalog is snapshotted and diffed* |
| Retire verb and versioning | `POST /v1/product-families/{id}/retire`; `/v1` as a URL path segment with one contract file per major. `PATCH` rejected outright; a header- or date-driven version pipeline rejected. | `java-backend-api` *`PATCH` is banned on every endpoint*, *The API version is a URL path segment* |
| Contract artifact and its gate | springdoc-openapi **2.9.0** emitting OpenAPI 3.1 from the booted application; a hand-owned normaliser (recursive key sort, pinned array order, LF, trailing newline) writes `backend/contracts/openapi.yaml`; a test regenerates it twice under varied default time zone and locale and fails `mvnw verify` on any byte difference. springdoc 3.1.0 rejected: it targets Boot 4. `springdoc-openapi-maven-plugin` rejected: a second application boot inside the build for no extra signal. | `java-backend-api` *One committed OpenAPI document, generated and diffed*, *One hand-owned canonical normalizer*; the tool pick is `NEW — proposed`, 2026-09-02 |
| Optimistic concurrency on the wire | No `version` column, no strong `ETag`, no `If-Match`, no guarded version-column helper in this feature. | Diverges from `java-backend-api` *Strong ETags, and when the precondition is honored* and *The guarded version-column update* — this feature exposes no client-supplied-precondition mutation: there is no `PUT`, `PATCH` is banned, and retire is idempotent, so a validator would guard nothing. The first full-replace update adds both. |
| Contract gates not wired | The conformance-fuzz oracle and the single-OS byte-identity regeneration are **not wired**, and are recorded as ungated rather than described as enforced. The breaking-change diff is dormant by that skill's own condition: no document has been released and no consumer outside this build binds to it. | Diverges from `java-backend-api` *The committed document is the single conformance oracle* and *Authoritative generation runs on one operating system* — this repository has no CI host and one build environment |
| Test toolchain | JUnit 5 at the version the Boot 3.5.16 BOM manages, plus Testcontainers **1.21.4** (`testcontainers`, `postgresql`) against `postgres:16.15-alpine` applying the real migrations. H2 and any in-memory substitute rejected. Testcontainers 2.0.5 and `junit-jupiter` 6.1.3 exist on Central and are rejected: neither is managed by the Boot 3.5 BOM, and overriding both would put the test stack outside the framework's tested set. | `java-backend-rules` *Integration tests run against real PostgreSQL* |
| Guardrail host | ArchUnit **1.5.0** (`archunit-junit5`) as one executable ban-list test class: injectable `DSLContext`, attached-record CRUD, `fetchOne`/`fetchAny`, plain SQL, wall-clock calls, `ORDER BY` on `id` outside the pager, field injection, `@Transactional`, `@Scheduled`, `@Async`, `@Cacheable`, `@PatchMapping`, UUID v4 generators, error bodies built outside the advice. | `java-backend-rules` *The ban list is an executable test class*, *Every ban names the check that enforces it* |
| Gates deliberately not wired in this feature | Error Prone + NullAway, pitest and the JaCoCo coverage floor are **not wired**; squawk migration lint is **not wired** (the one migration is `CREATE TABLE` plus an index on an empty table, and the binary is not in the build image). Each is recorded as an ungated rule, not as coverage. | Diverges from `java-backend-rules` *JSpecify, checked by NullAway, as compile errors*, *Coverage is gated by JaCoCo*, *Every migration is linted for lock and rewrite hazards* — compile-path plugins and a coverage floor on a repository's first commit are build fragility this feature declines |
| Structured logs | Spring Boot's built-in structured logging, `logging.structured.format.console=ecs`. `logstash-logback-encoder` 9.0 rejected: a dependency for what the framework already ships. | spec NFR-002 — feature-local; the tool pick is `NEW — proposed`, 2026-09-02 |
| Time and concurrency | One injected `Clock` bean, no wall-clock call in domain code, `timestamptz` storage, `Instant` on the wire as RFC 3339 UTC `Z` with `At`-suffixed field names; virtual threads via `spring.threads.virtual.enabled=true`. Fixed request thread pools and `StructuredTaskScope` rejected. | `java-backend-rules` *`Clock` is injected*, *Business dates are their own concept*, *Virtual threads are enabled by one property*, *No preview APIs*; `java-backend-api` *Instants on the wire* |
| Build tool | Maven with the committed wrapper, Maven **3.9.16**. No floating versions anywhere in `pom.xml`. | `java-backend-rules` *The pin is created at the newest supported LTS, and after that it is the pin* |
| Packaging | `backend/Dockerfile` from `eclipse-temurin:21-jre-alpine`, copying `target/*.jar`, `ARG APP_PORT` + `EXPOSE`, non-root user. `deploy/dev/docker-compose.yml` carries the service plus `postgres:16.15-alpine` with a named volume, a `healthcheck` on both (`/actuator/health` and `pg_isready`), `depends_on: condition: service_healthy`, `${VAR}` values read from an uncommitted `.env`, and a committed `.env.example`. Building inside the image rejected: the netos shape copies a finished artifact. | the DEPLOY stage's `deploy` skill §2, which owns and commits the compose project |
| Container image pinning | Both images referenced by tag, verified on Docker Hub 2026-09-02: `postgres:16.15-alpine` (newest 16.x alpine) and `eclipse-temurin:21-jre-alpine` (updated 2026-08-21; temurin publishes no patch-level alpine tag past `21.0.12_8`). Digest pinning declined for this feature and recorded as ungated. | Diverges from `llm-default-traps` *CI actions and scanners are SHA-pinned*, whose layer clause extends the shape to `FROM` lines |

## 10. Risks

A critique pass over this plan, written to find faults in it.

- **The idempotency claim on retire is the weakest thing here.** FR-011 is realised by "the
  guarded `UPDATE` affected no row, so re-read and return what is there" — and that branch is
  also reached when the row was deleted between statements, and when a future feature adds a
  third status. It is correct only because §5 says rows are never deleted and §3 declares exactly
  two statuses. Both are assumptions this design silently depends on. Cheapest early signal: a
  Testcontainers test that retires concurrently from two threads and asserts one `updated_at`.
- **The absent key-column default is a real hole, not a formality.** §5 names it; nothing
  enforces it. Any row written by a migration, a repair script or a psql session gets a null or a
  v4 id, and the first symptom is a scattered index nobody attributes to this decision. The
  cheapest signal is a test asserting every persisted `id` has version nibble 7.
- **`DDLDatabase` is a SQL parser, not PostgreSQL.** If the migration uses syntax jOOQ's parser
  renders differently from the server, the generated classes describe a schema the database does
  not have, and the integration tests are the only place that shows. It shows immediately for
  this one `CREATE TABLE`; it will not stay that cheap.
- **Duplicate-code detection depends on mapping one integrity violation to one error code.** The
  design reads the constraint name to do it. Rename the index in a later migration and FR-018
  silently degrades to a `500`. The catalog snapshot test does not catch that; only an
  integration test that inserts the same code twice does.
- **This plan promises three NFR tests and no CI runs them.** `mvnw verify` is the whole
  enforcement surface, and a latency test inside it is a test on the build machine, not on the
  dev deployment the spec's NFR-001 scopes. Read NFR-001's enforcement as "measured against the
  service under the same compose topology", and treat the number as a floor, not a guarantee.
- **What would make the whole approach wrong:** if product definitions turn out to need a
  compound key including the family, or if families turn out to be per-tenant. Either makes this
  table's shape wrong rather than incomplete. `OI-004` and the spec's single-signer assumption
  are where that would surface.

## 11. Phase plan

The spec ranks every requirement `Must` except FR-004 (`Should`). No phase delivers FR-004 ahead
of a `Must`: it ships in phase 3 beside the create operation it belongs to.

| Phase | Delivers | Satisfies |
|---|---|---|
| 1 | The `backend/` Maven module: pinned build, application entry point, injected `Clock`, virtual threads, structured logs, the Flyway migration, jOOQ generation from it, and the one UUIDv7 producer | FR-002, FR-003, FR-012, FR-018, NFR-002 |
| 2 | The transaction seam, the repository's explicit jOOQ statements, the domain service with validation and the retire transition, and the error catalog with the one advice | FR-001, FR-005, FR-006, FR-007, FR-008, FR-010, FR-011, FR-013, FR-015, FR-016, FR-017, FR-018, FR-019, FR-023 |
| 3 | The four HTTP operations — create, get, list with the pager and cursor, retire | FR-001, FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, FR-010, FR-011, FR-013, FR-019, FR-020, FR-021, FR-022 |
| 4 | The committed OpenAPI document and its regenerate-and-diff gate, the ArchUnit ban list, the health operation, the Dockerfile, and the three NFR tests | FR-014, NFR-001, NFR-002, NFR-003 |

The compose project at `deploy/dev/docker-compose.yml` is delivered by the **DEPLOY** stage from
§9's row, not by a phase here — it is under neither workspace, so no build stage owns it.

## Open items

Not a numbered section: these are decisions this plan could not close and did not close silently.

| ID | Item | Blocks | Owner |
|---|---|---|---|
| PI-001 | **This repository has no tier-map file**, so §7's entries are declared and unapplied. Which file holds the map, and creating it, is a T1 change belonging to the platform owner; this plan may not widen its own permissions by writing one. | tier-map completeness at merge — an undeclared path routes to T1 and fails the build naming the path | platform owner |
| PI-002 | The spec's `OI-001`, `OI-002` and `OI-003` are still open, and §3 and §6 encode the spec's proposed values (`[A-Z0-9]{3,20}`, `limit` 20/100, unfiltered list returns both statuses). If the domain owner answers differently, §3, FR-006, FR-016 and FR-020 change together. | nothing yet — the values are decided, not undecided | domain owner |
