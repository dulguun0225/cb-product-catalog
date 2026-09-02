<!-- PLATFORM-14 · implementation notes · Код + тест · 2026-09-02 -->

# Implementation notes — 001-product-family-catalog

The backend of `CB_PRODUCT_CATALOG`, T-001…T-012 of
[`specs/001-product-family-catalog/tasks.md`](../../specs/001-product-family-catalog/tasks.md),
built against the signed spec, plan and LLD in that folder. This file records what a reader of
the repository alone cannot recover: how to build it, where the implementation reports something
back to the approved artifacts, and which rules are stated but ungated.

## Building it

There is no JDK or Maven on the machine this was built on. Everything ran through the committed
wrapper inside a container:

```sh
docker run --rm --network host \
  -v "$PWD":/repo -v m2repo:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
  -w /repo/backend maven:3.9.11-eclipse-temurin-21 mvn -B verify
```

`--network host` and the Docker socket are what let Testcontainers start `postgres:16.15-alpine`
as a sibling and be reachable. `./mvnw -B verify` is the same build where a JDK is present.

**One full `verify` runs 108 tests and takes about two minutes**, of which 60 seconds is
`GetFamilyLatencyTest` holding 50 requests per second for a minute — that duration is NFR-001's,
not a choice.

## Reported back to the approved artifacts

Each of these is a place the implementation could not simply follow the text. None was applied
silently; each is in the commit that made it.

| # | Where | What |
|---|---|---|
| 1 | lld §3, `FamilyIdsGoldenTest` | The design asks for a UUIDv7 timestamp field "increasing across successive calls under a `Clock.fixed`". Under a genuinely fixed clock that field is constant by construction. The test pins the true, stronger pair instead: it **equals** the fixed clock's instant, and **increases** when the clock advances. |
| 2 | `contracts/openapi.yaml`, `FamilyNotFound` | The prose says the response "never echoes the submitted segment"; the same response's own example carries the path segment in `instance`. Resolved toward the example. The test asserts the property FR-019 actually secures — `type`, `title`, `status`, `detail`, `code` and the member set are identical for a malformed segment and an absent one, so nothing in the body discriminates them. |
| 3 | lld §2, `KeysetPager` | The design puts `KeysetPager` in `api/` and the ordered statement in `persistence/`. `primary-keys` scopes its `ORDER BY`-on-id carve-out to "that one pager class". The two seams are therefore split: `KeysetPager` renders the page, `ProductFamilyRepository#findPage` renders the ordered statement, and `BanListTest` scopes the exemption to the latter. All four of the carve-out's constraints still hold. |
| 4 | lld D-08 / OI-006 | Left exactly as the approved artifacts leave it. The framework's protocol-level failures — 400 on an unreadable body, 405, 406, 415 — keep the RFC 9457 shape and carry **no `code`**, because the approved eight-member catalog has no member for them and adding one edits an approved document. `ProblemDocuments.uncoded` is where they are built, so "one factory builds every error body" stays literally true. |

## Defects the tests found, and the code changes that answered them

Written down because the fix is invisible once applied, and each was a requirement the
implementation was quietly failing.

- **`limit=` and a repeated `limit=5&limit=7` silently defaulted to 20.** Spring treats an empty
  parameter as absent and binds a repeated one to its first value. Both are the silent defaulting
  FR-020 forbids beside silent clamping, and neither reaches a binding failure, so neither reached
  the advice. `ProductFamilyController` now rejects both against the raw parameter values.
- **`ErrorPathController` built its own `ProblemDetail`** for the uncoded protocol failures,
  breaking the one-factory rule. Found by `BanListTest`; moved into `ProblemDocuments.uncoded`.
- **The `DSLContext` ban was too broad.** Written as "no dependency", it banned the compliant
  shape — the repository *receiving* a context as the seam's lambda parameter — along with the
  banned one. Narrowed to *holding* one as a field or constructor parameter. A rule that bans the
  right answer gets relaxed rather than obeyed.
- **`timestamptz` keeps microseconds and `Instant` keeps nanoseconds.** Without truncation an
  instant read back differs from the one written, and FR-011 asks for a body identical to the
  first retire's. `ProductFamilyService` truncates to microseconds before writing.

## Where no check reaches

Restated from lld §7 for a reader who has only this repository. A green `verify` does **not**
cover these:

1. The `id` column has no schema default — native `uuidv7()` is a PostgreSQL **18** function and
   this deployment is pinned to 16. A row written by a later migration, a repair script or a
   database session can carry a null or a v4 id. `FamilyIdsGoldenTest` reaches application writes
   only.
2. The clock ban's store-language half (no `DEFAULT now()`, no trigger) is asserted for *this*
   migration by `MigrationSchemaIT`; no lint stops the next one.
3. `ORDER BY id` inside query text, a view or a function. ArchUnit reads bytecode; the plain-SQL
   ban narrows the exposure but does not close it.
4. Maven Enforcer (`requireJavaVersion`, `banDynamicVersions`) is not wired — design OI-009.
5. Container images are tag-pinned, not digest-pinned.
6. Error Prone + NullAway, JaCoCo, pitest and the squawk migration lint are not wired (plan §9).
7. The conformance-fuzz oracle is not wired. The drift gate proves the document equals itself;
   that the *service* matches it is proved by `HealthIT` and the four operation integration tests.
8. `spring.threads.virtual.enabled` and `logging.structured.format.console` are checked as
   committed defaults; an environment variable can still override either at runtime.
9. **This repository still has no tier-map file** (plan PI-001, design OI-010), so every path this
   change touches is undeclared and routes to T1 at merge.

## Open items this stage did not close

`OI-006` (a ninth error code for the protocol-level failures), `OI-007` (whether the cursor
sealing key is worth its operational cost), `OI-008` (the spec's `OI-001`–`OI-003`: the family-code
character set, the `limit` default and maximum, and whether an unfiltered list includes `RETIRED`
— the code encodes the spec's proposed values, `[A-Z0-9]{3,20}`, 20/100, and both statuses),
`OI-009` and `OI-010`. Each needs a re-signed artifact, not a code change.
