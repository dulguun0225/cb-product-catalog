# Spec — Product family catalog

| | |
|---|---|
| **Feature** | `001-product-family-catalog` |
| **Repository** | `dulguun0225/cb-product-catalog` (system `CB_PRODUCT_CATALOG`) |
| **Authored** | `2026-09-02` |
| **Source material** | see the register below |
| **Signer** | spec gate — the domain owner (T1). At T2 the plan signer asserts this too. |
| **Assertion** | *This is the right problem, and this is what "done" means.* |

### Source register

| ID | Document | Access | Revision | Governs |
|---|---|---|---|---|
| SRC-001 | Feature request — "Product family catalog", the drafting task statement for this pipeline test | `attached` | the drafting turn, 2026-09-02 | the whole feature: the entity and its fields (scope bullet 1), the operations and their failure responses (scope bullet 2), the health operation (scope bullet 3), and the operational properties (non-functional ¶) |

No other document was named, and nothing was fetched — there is no web and no shell at this stage.

## 1. Purpose and scope

The core-banking product catalog service needs a place to register the **product families** that
every later product definition will hang off. This feature is that place: a family carries an
opaque identifier, a human-facing family code, a name and a lifecycle status, and the service
lets a client create one, read one, list them a page at a time, and retire one. It is the
smallest set that can be observed working on its own — a read path with nothing to create is not
shippable, and a create path with nothing to read cannot be checked.

**Out of scope:** product definitions, versions, pricing and any other content *inside* a
family; authentication and authorisation of API clients; any user interface; importing families
from a predecessor catalog; deleting a family (retirement is the only withdrawal).

| Excluded | Where it lives instead | Boundary rule |
|---|---|---|
| Product definitions, versions and pricing inside a family | a later feature of `CB_PRODUCT_CATALOG` — no folder drafted yet, `unowned — OI-004` | anything whose lifecycle is not the family's own |
| Authentication and authorisation of API clients | `unowned — OI-005` | this feature declares no role, reads no token and returns no `401` or `403` |
| Any user interface over the catalog | nothing — this feature ships no frontend | any file that would live under `frontend/` |

## 2. Definitions

### Actors

*Actors:* API client, deployment health checker.

### Terms

- **API client** — a core-banking service or integrator calling this service's HTTP contract.
  This feature distinguishes no roles among clients.
- **Deployment health checker** — the container orchestrator's probe, and the deploy stage's
  `curl`, calling the health operation. It reads no family data.
- **Product family** — a named grouping under which product definitions are later registered.
- **Opaque identifier** — the machine identifier of a family: assigned by the service, carried in
  URLs, logs and payloads, and meaningless to read. No client supplies or chooses one.
- **Family code** — the short human-facing handle for a family, keyed and read aloud by people.
  Supplied by the client at creation, unique across the service, and never changed afterwards.
- **Status** — `ACTIVE` or `RETIRED`; the two states of §3's model and the only values a status
  filter accepts.
- **Created-at / updated-at** — the instants at which the service first persisted a family and
  last changed it, read from the injected clock, never from a wall-clock call in domain code.
- **Injected clock** — the one time source domain code reads; there is no other.
- **Keyset page** — a result window addressed by the sort values of the last row returned, never
  by a row count or a page number.
- **Cursor** — the opaque, integrity-sealed encoding of a keyset page position, issued by the
  service. Clients pass one back unmodified and never construct one.
- **Declared maximum** — the largest `limit` the list operation accepts. Proposed 100, with a
  default of 20 (`OI-002`).
- **Problem document** — an RFC 9457 `application/problem+json` body.
- **Error code** — the stable machine-readable member of a problem document, drawn from one
  catalog. Clients branch on it and never on `title` or `detail` prose.
- **Correlation identifier** — the per-request identifier carried in logs and in the `500`
  problem document, and the only internal detail an unhandled failure exposes.

## 3. Functional requirements

### State model

*States:* `ACTIVE`, `RETIRED`. *Initial:* `ACTIVE`. *Terminal:* `RETIRED`.

| From | Trigger | Guard | To | FR ids |
|---|---|---|---|---|
| `ACTIVE` | retire request | — | `RETIRED` | FR-010 |
| `RETIRED` | retire request | — | `RETIRED` | FR-011 |

A family is created directly into `ACTIVE`; `FR-001` is the requirement that puts it there.
`RETIRED` is terminal because `FR-013` denies every exit from it.

### Requirements

- **FR-001** WHEN an API client submits a create-family request whose body satisfies every field constraint in §2, the service shall persist a new product family with status `ACTIVE`.
  *Priority:* `Must` · *Source:* SRC-001 scope bullets 1–2
- **FR-002** WHEN the service persists a new product family, the service shall assign it an opaque identifier that no client supplied.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 1
- **FR-003** WHEN the service persists or changes a product family, the service shall record the created-at and updated-at instants from the injected clock.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 1
- **FR-004** WHEN the service has persisted a new product family, the service shall respond `201 Created` with a `Location` header addressing that family by its opaque identifier.
  *Priority:* `Should` · *Source:* `derived`
- **FR-005** WHEN an API client requests a product family by its opaque identifier, the service shall respond with that family's opaque identifier, family code, name, status, created-at and updated-at.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 2
- **FR-006** WHEN an API client requests the family list with no status filter, the service shall include families of every status in the result.
  *Priority:* `Must` · *Source:* `derived`
- **FR-007** WHEN an API client requests the family list with a status filter, the service shall include only families whose status equals the filter value.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 2
- **FR-008** WHEN an API client requests the family list, the service shall order the result by family code ascending with the opaque identifier as the final tiebreak.
  *Priority:* `Must` · *Source:* `derived`
- **FR-009** WHEN an API client requests the family list, the service shall return a non-null cursor only where a further page exists.
  *Priority:* `Must` · *Source:* `derived`
- **FR-010** WHILE a product family is `ACTIVE`, WHEN an API client submits a retire request for that family, the service shall transition it to `RETIRED`.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 2
- **FR-011** WHILE a product family is `RETIRED`, WHEN an API client submits a retire request for that family, the service shall respond with that family unchanged.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 2
- **FR-012** The service shall expose no operation that changes a persisted family code.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 1
- **FR-013** The service shall expose no operation that transitions a product family out of `RETIRED`.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 2
- **FR-014** The service shall expose a health operation reporting whether it is able to serve requests.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 3
- **FR-015** The service shall render every error response as a problem document carrying an error code drawn from one catalog.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 2
- **FR-016** IF a create-family request carries a family code that is not 3 to 20 characters drawn from `A`–`Z` and `0`–`9`, THEN the service shall reject the request with a `400` problem document carrying error code `FAMILY_CODE_INVALID`.
  *Priority:* `Must` · *Source:* SRC-001 scope bullets 1–2
- **FR-017** IF a create-family request carries a name that is not 1 to 120 characters, THEN the service shall reject the request with a `400` problem document carrying error code `FAMILY_NAME_INVALID`.
  *Priority:* `Must` · *Source:* SRC-001 scope bullets 1–2
- **FR-018** IF a create-family request carries a family code a persisted family already holds, THEN the service shall reject the request with a `409` problem document carrying error code `FAMILY_CODE_DUPLICATE`.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 2
- **FR-019** IF a request addresses a product family by an opaque identifier no persisted family holds, THEN the service shall reject the request with a `404` problem document carrying error code `FAMILY_NOT_FOUND`.
  *Priority:* `Must` · *Source:* SRC-001 scope bullet 2
- **FR-020** IF a list request carries a `limit` above the declared maximum, THEN the service shall reject the request with a `400` problem document carrying error code `LIMIT_ABOVE_MAXIMUM`.
  *Priority:* `Must` · *Source:* `derived`
- **FR-021** IF a list request carries a cursor that fails its integrity check or whose sort specification does not match the request, THEN the service shall reject the request with a `400` problem document carrying error code `CURSOR_INVALID`.
  *Priority:* `Must` · *Source:* `derived`
- **FR-022** IF a list request carries a status filter value that is neither `ACTIVE` nor `RETIRED`, THEN the service shall reject the request with a `400` problem document carrying error code `STATUS_FILTER_INVALID`.
  *Priority:* `Must` · *Source:* `derived`
- **FR-023** IF an unhandled failure occurs while serving a request, THEN the service shall respond with a `500` problem document carrying error code `INTERNAL_ERROR` and a correlation identifier as its only internal detail.
  *Priority:* `Must` · *Source:* `derived`

## 4. Non-functional requirements

| ID | Property | Metric | Threshold | Window | Scope | Enforcement | Source |
|---|---|---|---|---|---|---|---|
| NFR-001 | read latency of the family-by-identifier operation | `request-duration` | p95 ≤ 200 ms at 50 requests per second | per test run | `GET` of one family by opaque identifier, on the dev deployment | `test` | SRC-001 non-functional ¶ |
| NFR-002 | log readability without a human reader | share of application log lines that parse as a single-line JSON object | 100% | per test run | every log line the service emits | `test` | SRC-001 non-functional ¶ |
| NFR-003 | secrets absent from the repository | count of credential values committed to the repository | 0 | per build | every committed file | `test` | SRC-001 non-functional ¶ |

## 5. Success criteria

- **SC-001** An integrating core-banking service can register, read, page through and retire
  product families using only the published contract, without reading this service's source.
- **SC-002** The number of product families sharing a family code stays at zero for the life of
  the service.
- **SC-003** Every error an API client receives can be branched on by its error code alone; no
  client needs to parse `title` or `detail` prose.
- **SC-004** A retired family stays retired: no observed family leaves `RETIRED`.

## 6. Key entities

**Product family** — the only entity this feature introduces.

| Attribute | Meaning |
|---|---|
| opaque identifier | machine identity, assigned by the service, the target of every future foreign key and the only identifier in a URL |
| family code | human-facing handle, client-supplied, unique across the service, immutable, stored exactly as supplied |
| name | the family's display name, 1–120 characters |
| status | `ACTIVE` or `RETIRED` |
| created-at | instant of first persistence |
| updated-at | instant of last change |

It has no relationships in this feature. Product definitions will later reference a family by its
opaque identifier, never by its family code.

## 7. Open items

| ID | Item | Blocks | Owner | Due |
|---|---|---|---|---|
| OI-001 | Family-code character set: `A`–`Z` and `0`–`9` only (as FR-016 states), or are `-` and `_` also permitted? Widening later is backward compatible; narrowing is not. | FR-016's exact accepted set | domain owner | before the spec gate |
| OI-002 | List `limit`: is the proposed default of 20 and declared maximum of 100 right for the expected number of families? | FR-020's threshold and §2's declared maximum | domain owner | before the spec gate |
| OI-003 | Should the unfiltered family list include `RETIRED` families, as FR-006 states, or default to `ACTIVE` only? | FR-006 | domain owner | before the spec gate |
| OI-004 | Product definitions inside a family are excluded but no sibling feature owns them yet. | the §1 out-of-scope destination | requester | before the plan gate |
| OI-005 | Nothing owns authentication and authorisation for this service's API. The dev deployment will be unauthenticated. | the §1 out-of-scope destination | requester | before the dev deployment is reachable outside the network |

## 8. Assumptions

- **FR-004 — `derived`**: concluded from the shape of an HTTP create operation and from the
  distinction §2 draws between the opaque identifier and the family code — the `Location` header
  addresses the family by its identifier because the family code is not a URL identifier. The
  request does not mention response headers.
- **FR-006 — `derived`**: concluded from the request calling the status filter *optional*, which
  implies an unfiltered result exists and returns everything. See `OI-003`.
- **FR-008 — `derived`**: concluded from the request asking for keyset pagination, which needs a
  deterministic total order; family code is the only client-meaningful sort column this entity
  has, and the opaque identifier is appended solely to break ties. Identifier order is an
  explicit non-property of this ordering — nothing may depend on it.
- **FR-009 — `derived`**: concluded from the request asking for keyset pagination; a paging
  contract that cannot say "this was the last page" is not usable.
- **FR-020 — `derived`**: concluded from the request asking for pagination without naming a page
  size; a `limit` with no ceiling is an unbounded read. Rejecting rather than silently clamping is
  the choice, so a client that asked for 5000 rows learns it did not get them. See `OI-002`.
- **FR-021 — `derived`**: concluded from the cursor being service-issued and opaque; a cursor the
  service cannot vouch for cannot be turned into a best-effort read.
- **FR-022 — `derived`**: concluded from status having exactly the two values §2 declares; a
  filter value outside them is a client error, not an empty result.
- **FR-023 — `derived`**: concluded from the request asking for RFC 9457 problem documents with an
  error-code catalog, which is only complete if the unhandled case also has a code. Restricting
  the body to a correlation identifier follows from nothing in the request; it is the assumption
  that internal failure detail is not part of this contract.
- **FR-016's character set** is an inference, not a statement: the request says "uppercase, 3–20
  chars" and does not say whether separators are allowed. `OI-001` asks.
- **The declared maximum and default `limit`** in §2 are inferences from nothing in the request.
  `OI-002` asks.
- **Priorities** are inferred throughout, not given. Everything the request lists as scope is
  `Must`; FR-004 is `Should` because the operation is observable without the header.
- **One signer** is assumed: this whole feature answers to the product-catalog domain owner. If
  authorisation (`OI-005`) turns out to belong to a second owner, that is a second feature.

## 9. Source coverage

| SRC | Requirements drawn from it | Read and deliberately not used | Where that went instead |
|---|---|---|---|
| SRC-001 | FR-001, FR-002, FR-003, FR-005, FR-007, FR-010, FR-011, FR-012, FR-013, FR-014, FR-015, FR-016, FR-017, FR-018, FR-019, NFR-001, NFR-002, NFR-003 | the stack, build, contract-artifact and deployment directives — Java, Spring Boot, jOOQ, PostgreSQL, Flyway, Maven, the committed OpenAPI document, the Docker image, the compose file | `plan.md` — they decide *how*, and this document states only what the system shall do |
| SRC-001 | — | the directives about this document's own format, section numbering and line budget | not feature behaviour; they govern the artifact, not the system |

There are no gaps: the one registered source produced 18 cited requirements, and both parts of it
that produced none are recorded above with where they went. The register holds no paraphrased row
and no ruled-out row, and there is no conflict — with a single source there is nothing for a
precedence order to resolve.
