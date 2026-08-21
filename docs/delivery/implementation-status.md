# River implementation status

<!-- markdownlint-disable MD013 -->

Last updated: 2026-08-21

This ledger records promotion evidence against the deliverable IDs in the
[implementation plan](../plans/river-project-implementation-plan.md). A source
file or passing unit test is not, by itself, evidence that a broad deliverable
or gate has passed.

## Status vocabulary

| Status | Meaning |
| --- | --- |
| `not-started` | No accepted implementation evidence exists. |
| `active` | Implementation or evidence collection is in progress. |
| `implemented` | Code exists, but one or more required reviews or gates remain. |
| `passed` | All named evidence is present and independently reviewed. |
| `deferred` | The plan deliberately postpones this optional capability. |

## Current execution

| Field | Value |
| --- | --- |
| Integration branch | `master` |
| Current wave | M5 useful v1 SQL surface |
| Current target | [Bounded n-table JOIN merge strategy and cost planning](../plans/m5-n-table-joins.md) |
| Next product slice | [P4C robust computed/correlated subqueries](../plans/m5-p4c-subqueries.md), then [online schema evolution](../plans/m5-online-schema-evolution.md) |
| Lead integrator | Primary implementation agent |
| Latest green functional checkpoint | `4c50133` (2026-08-21) — bounded two-to-eight-role INNER/LEFT chains through direct/P3/order/spill plans; UTF-8-v4 durable direct/deepest-derived and self-join views with exact 32-slot ordered lineage, checkpoint/WAL reopen, dependency enforcement and backup/restore; bounded typed hash execution with explicit stable spill fallback — full `river-sql`, `river-engine`, and `river-backup` suites plus allocation, design-debt, and independent semantic/durability reviews green; prior checkpoints retained |
| Verified integration checkpoint | `a9c5a07` — detached offline/uncached 149-task check and reproducible 58-archive build |

The bytecode-policy and clean-checkout gates are integrated, independently
reviewed, and verified together from the exact detached integration commit.

### Active branch checkpoints

| Purpose | Branch | Commit | Status |
| --- | --- | --- | --- |
| Shipped bounded-join alpha | `master`, `feature/n-table-joins` | `4c50133` | Clean, pushed, and accepted through J5; J6 merge and J7 costing remain planned |
| P4C recovery snapshot | `wip/p4c-subqueries-snapshot` | `794641e` | Clean and pushed immutable checkpoint of the pre-integration work |
| P4C continuation | `feature/p4c-subqueries` | `19abbff` | Clean, pushed, rebased onto `4c50133`, and main-source compile green; not acceptance-ready |

P4C resumes from `19abbff`. Its first gates are to migrate the stale parser and
lifecycle tests away from the deleted singleton subquery API, then correct the
null zone-state failure exposed by the joined P3 spill regression. The snapshot
branch is recovery evidence only and must not be merged in place of the rebased
continuation branch.

Product work proceeds in this order:

1. [Bounded n-table JOIN execution and durable views](../plans/m5-n-table-joins.md)
   with complete dependency lineage.
2. [P4C robust computed/correlated subqueries](../plans/m5-p4c-subqueries.md).
3. [Online schema evolution](../plans/m5-online-schema-evolution.md):
   `ALTER TABLE`, online index creation/removal, and transactional changes to
   foreign keys, views, constraints, defaults, and generated values.
4. [Durable subquery views](../plans/m5-durable-subquery-views.md) over the
   admitted P4C and n-table source graph.
5. Broader CHECK expression semantics not required by the preceding slices.
6. JDBC feature additions and known JDBC issues.
7. Core crash/recovery, isolation, fault, bounded-growth, and soak promotion.

Lower-ranked work does not interrupt a higher-ranked slice unless it prevents
that slice from executing through the embedded engine path.

K16 and U00 completed on 2026-08-12, satisfying their temporary priority
override. Their focused ownership, recovery, SQL-semantics, allocation, and
policy gates passed; later changes remain consumer-triggered rather than a new
general refactoring lane. The delivery priority therefore returns to the
single-node product path. N01-N08/G3B completed for the bounded authenticated
loopback service on 2026-08-14. Work is now on U01-U06/M5; G1/G2 crash,
isolation, and bounded-growth evidence remains required for promotion and is
gathered proportionately alongside that product slice.

The committed functional path now spans recoverable heap/B+tree storage,
concurrent MVCC transactions, transactional SQL/catalog operations, embedded
sessions, authenticated TLS transport, streaming JDBC, CLI, quiescent
backup/restore, and offline inspection. The current SQL surface includes
constraints, sequences/identity, views, joins, aggregation, sorting/spill, and
bounded nested/correlated queries. These implementations remain subject to the
status and promotion dependencies below.

The historical `e4a02dc` checkpoint contains 399 declared tests and passed the
ordinary repository-wide test task. Later accepted K16, U00, ownership, and
complexity checkpoints extend that functional baseline. None substitutes for
the detached clean-checkout, crash, performance, security, compatibility, or
operations evidence required by G0-G4.

P00 is passed and Phase 0 is no longer the execution focus. Formal M0/G0
promotion remains incomplete in the ledger, but residual foundation work is
performed only when an immediate kernel consumer requires it. It does not
block functional implementation whose immediate dependencies are present.

## Type and temporal delivery

The current implementation admits `BIGINT`, general UTF-8 `VARCHAR(n)`,
`BOOLEAN`, exact `DECIMAL(p,s)`, `DATE`, `TIME(p)`, local `TIMESTAMP(p)`, and
`TIMESTAMP(p) WITH TIME ZONE` with session-zone/current-value semantics. The
ordered remaining delivery is U02f/U06a; normative semantics are in the
SQL conformance profile.

| Slice | Status | Next evidence |
| --- | --- | --- |
| U02a type contract/descriptors | passed | Accepted canonical descriptors, typed catalogs/results, coercion families, and fail-closed decode evidence |
| U02b general UTF-8 `VARCHAR(n)` | passed | Accepted variable-width rows/indexes/spill, Unicode ordering, boundary round trips, backup, and recovery evidence |
| U02c `BOOLEAN` and `DECIMAL(p,s)` | passed | Accepted exact representation/math/casts, predicate/aggregate/index/constraint, JDBC, recovery, and error evidence |
| U02d local temporal types | passed | Accepted strict local temporal grammar/codec, catalog/row/default/check validation, DATE index, DML/predicates/update, corruption, and checkpoint/reopen evidence; TIME index admission was corrected in U02f after proving its full domain fits the existing key |
| U02e time-zone semantics | passed | Accepted UTC instant storage, strict fixed/IANA area zones, DST gap/overlap behavior, session-state rollback semantics, tzdb reporting, statement-stable defaults, catalog v13, and checkpoint/reopen evidence |
| U02f temporal durability/expressions | active | Accepted checkpoints cover direct-root expressions and mutations, generalized scalar/grouped `HAVING`, durable owner-column `CHECK`, projection composition, block-scoped aggregate/`DISTINCT` stages through derived tables, P4A's common bounded Boolean/3VL program, and bounded two-to-eight-role computed `INNER`/`LEFT JOIN` chains through direct/P3/order/spill execution. Durable views use strict UTF-8-v4 ordered lineage for up to 32 physical roles, including aliased self-joins, through checkpoint/reopen and backup/restore. Typed hash equality is admitted in memory with explicit bounded spill fallback; merge/cost planning, computed correlation, and broader CHECK/expression contexts remain separate work. |
| U03a JDBC/protocol types | passed | Accepted protocol v3 binary values/parameters, authenticated all-type JDBC/CLI, Java-time/decimal mappings, exact metadata, conversion matrix, warnings, generated keys, bounded batches, failure states, and ownership/erasure evidence |
| U06a type/temporal gate | active | Unified embedded/authenticated-JDBC/CLI/checkpoint/backup/fault fixture and independent relational-semantics review remain |

## Foundation evidence

| ID | Status | Promotion evidence still required |
| --- | --- | --- |
| P00 | passed | Accepted charter and independently reviewed qualitative workload envelope; numeric envelopes remain P05-owned |
| P01 | passed | Accepted v2 provenance ledger, exact dependency verification, and reproducible external-reference snapshots with independent review |
| P02 | implemented | Combined clean-checkout verification and promotion review; deterministic style/source/bytecode validation is integrated and an automated reformatter is deliberately deferred |
| P03 | implemented | P02 promotion and final foundation gate review |
| P04 | passed | Accepted River-owned SQL profile and bounded selected-reference policy; exhaustive legacy compatibility remains explicitly out of scope |
| P05 | active | Current physical-host calibration, repeated raw measurements, numeric budgets, and performance review; Ingres comparison is optional |
| P06 | active | Accepted coupled ADR bundle and independent reviews |
| P07 | active | Replace the journal seam's already-used raw local WAL generation with a semantic type; then P02/P03 promotion, declared-host numeric allocation budgets, and final promotion review |
| P08 | implemented | P02/P03/P07 promotion and final foundation review; later slice crash registries remain at their owning gates |
| P09 | implemented | Declared-host P05 measurements, numeric budgets, expanded mechanism evidence, and final performance review |
| P10 | implemented | Numeric P05/P09 budgets, accepted P06 dependencies, and final provider-neutral vocabulary review; production conformance is K04/G1 evidence |
| G0 | active | P00-P10 evidence and independent gate review |

P01/P04 have project-owner decisions, executable evidence, and independent
review. P05 and P06 cannot be marked passed solely by code generation. In particular,
placeholder budgets do not satisfy P05 and unreviewed ADRs do not satisfy P06.

## Functional delivery evidence

The statuses below include accepted implementation through the 2026-08-14
working checkpoint; none marks its enclosing promotion gate as passed unless
the status explicitly says `passed`.

| IDs | Status | Current code evidence | Promotion work still required |
| --- | --- | --- | --- |
| K01-K04 | implemented | Durable control and format codecs, local WAL, force/read paths, and typed transaction/journal contracts | G0 dependencies, filesystem matrix, crash/failure matrix, and provider contract review |
| K05-K06 | active | Checked page stores, allocation/growth, dirty-state and checkpoint integration used by the functional engine | Complete buffer/writeback/tablespace contracts, WAL-before-page proof, bounds, and performance evidence |
| K07-K08 | implemented | Recoverable slotted heap rows plus unique and duplicate multi-level B+tree point/range paths | Model tests, complete structural crash matrix, format fixtures, and performance gate |
| K09-K15 | active | Transaction outcomes, rollback, checkpoints, restart/reopen, torn-page repair, inspector, backup primitives, and a recoverable heap+B+tree vertical path | Complete analysis/redo/undo and CLR evidence, generated crash registry, corruption policy, and G1 review |
| K16 | passed | One indexed-table kernel, explicit durable-store ownership, isolated WAL codec, phase-bound page state, group commit, vacuum, recovery, and exact compiled directionality | Completed 2026-08-12; future changes require a named storage consumer and preserve the K16 recovery/allocation gates |
| T01-T05 | implemented | Atomic bounded write sets, commit publication, MVCC snapshots, key/range locking, serializable validation, deadlock resolution, statement rollback, and named savepoints | Isolation histories, crash-during-rollback matrix, lock bounds, and independent correctness review |
| T06 | active | Automatic obsolete-version reclamation, snapshot draining under version pressure, and cross-WAL version compaction | Long-snapshot/vacuum/status-store growth and soak evidence |
| T07-T08 | implemented | Transactional durable catalog objects and the internal/embedded command path | Catalog upgrade/invalidation review and stable boundary evidence |
| T09 | active | Focused concurrency, deadlock, rollback, index-visibility, and version-pressure tests exist | Complete G2 history, fault, bounded-growth, and independent review package |
| Q01-Q02 | implemented | Bounded parser/query model, durable names, aliases, NULL semantics, and correlation across as many as 32 query blocks | Final Q01 conformance-profile acceptance and complete binder semantic fixtures |
| Q03-Q05 | active | Executable plans and `EXPLAIN`, indexed/scan choices, reusable result carriers, bounded sort spill, joins, grouping, and aggregates | General cost/capability interfaces, plan-cache/invalidation proof, vector-batch execution, memory governor, and performance evidence |
| Q06-Q08 | implemented | Transactional DDL/DML, indexes, immediate constraints, generated keys, embedded lifecycle/sessions, and streaming results | G1/G2 regression evidence, public API review, and G3A promotion |
| N01-N05 | passed | Accepted bounded versioned protocol, client/server lifecycle, TLS, exporter-bound token authentication, deadlines, cancellation, and fuzz evidence for loopback scope | Non-loopback service exposure remains consumer-triggered |
| N06 | passed | Accepted configured service-principal authorization, row filtering, bounded durable audit, admission, and fail-closed exhaustion/corruption behavior | SQL-managed roles/grants wait for a multi-principal consumer |
| N07 | passed | Accepted G3B JDBC connection/statement/result-set, transaction/savepoint, batch, generated-key, metadata, and failure-state subset | U03 completes the broader v1 type/conversion/warning matrix |
| N08 | passed | Authenticated TLS JDBC passes the real bounded loopback server path, slow-client/resource, fuzz, rollback, and audit gates | Non-loopback binding remains unavailable by design |
| U00 | passed | SQL session is a small facade over owned binding, transaction, DML, query, nested-query, sort/spill, dispatch, and cursor-lifecycle components | Completed 2026-08-12; later deepening is consumer-triggered and must preserve SQL semantics and warmed allocation gates |
| U01 | active | Inner/left joins, aggregates, grouping/HAVING, `DISTINCT`, ordering, spill/merge, comparisons, conjunction/disjunction, and nested/correlated query forms | N08/G3B, then typed expression/cast breadth, temporal predicates/functions, plan/performance budgets, and `EXPLAIN ANALYZE` gate evidence |
| U02 | active | U02a-U02e are passed: canonical descriptors, variable UTF-8 rows, BOOLEAN/DECIMAL, local/zoned temporal types, session zones, and current defaults join the transactional SQL path | U02f wide temporal indexes, temporal functions/context-wide durability, and profile fixtures |
| U03 | passed | Binary typed parameters/results, authenticated all-type JDBC/CLI, Java-time/decimal mappings, exact nullability, supported/rejected conversion matrix, warnings, generated keys, bounded batch failures, and ownership/erasure evidence are accepted | New conversions require an explicit matrix change and focused evidence |
| U04 | passed | Authenticated typed CLI, quote-aware bounded scripts, SHOW TABLES/INDEXES/COLUMNS, and EXPLAIN diagnostics pass real-path tests | Later production observability/system relations remain O04 rather than hidden M5 debt |
| U05 | passed | Three provenance-linked independent legacy semantic adaptations, report, matrix, v2 snapshot verification, and independent review are accepted | New selected references require new exact matrix rows and review |
| U06 | active | Useful SQL works end to end through JDBC and CLI in focused tests | U01-U05 completion and G1/G2/G3B regression gate |
| O01-O02 | active | Quiescent manifest backup/restore and offline physical inspection reject tested corruption | Online backup boundary, restore recovery, complete verify/classification, repair plans, and operations gates |
| O03 | not-started | Migration module remains unwired and empty by design | M1-M5 path must be unblocked, then one Ingres source adapter and rehearsal evidence |
| O04 | active | Bounded observability contracts and low-level diagnostic primitives exist | Production metrics/events/JFR, health, system views, redaction, and cardinality review |
| O05-O07 | not-started | No accepted production admin, packaging/upgrade, or operational-soak evidence | Complete Phase 4 implementation and G4/O07 rehearsal |
| R20-R60 | not-started | No production replication implementation evidence | G4 plus the staged R0-R3 contracts, simulator, provider, follower, state-sync, and failover gates |
| E01-E05 | deferred | Vector/semantic search has an architecture plan; no optional production capability is implemented | G4, a named workload, and each capability's independent measured gate |

## Milestone summary

| Milestone | Status | First missing hard dependency |
| --- | --- | --- |
| M0 Architecture ready | active | P01/P02 review and P05 declared-host measurements |
| M1 Recoverable indexed table | implemented | G0 dependencies and G1 crash/model/performance promotion evidence |
| M2 Concurrent transactional kernel | active | G1 promotion, then complete G2 isolation/bounded-growth evidence |
| M3 Embedded relational database | implemented | G1/G2 regressions, Q03-Q05 completion, and G3A review |
| M4 Secure JDBC database | passed | Bounded authenticated TLS loopback JDBC scope accepted through N01-N08/G3B on 2026-08-14 |
| M5 Useful v1 SQL surface | active | Remaining U01/U02f profile work and U06 end-to-end gate |
| M6 Single-node operational beta | active | M5/U06 followed by O01-O07 and G4 |
| M7 Durable full-replica journal | not-started | G4 and R20-R23 |
| M8 Operational failover | not-started | R2/R26 |
| M9 Optional capabilities | deferred | R3 and independent measured gates |

`implemented` at M1/M3 means that the named functional vertical path exists;
it does not mean the milestone has passed. Milestones pass only when the gate
in the implementation plan records the required independent evidence.

## Promotion gate summary

| Gate | Status | Current position |
| --- | --- | --- |
| G0 | active | Foundation implementations exist; declared-host numeric budgets, coupled ADR acceptance, residual reviews, and the independent gate decision remain open. |
| G1 | active | Recoverable heap/B+tree behavior exists; the exhaustive crash/model/format/performance package is incomplete. |
| G2 | active | MVCC, locking, rollback, deadlock, serializable ranges, and reclamation work; the complete history and bounded-growth package is incomplete. |
| G3A | active | The embedded SQL vertical path works; prerequisite gate regressions, planner/execution completion, API review, and promotion evidence remain open. |
| G3B | passed | Authenticated TLS loopback JDBC, authorization/audit, 100,000-case protocol mutation fuzzing, slow-client/resource lifecycle, rollback, and JDBC-state evidence accepted on 2026-08-14. |
| G4 | active | Quiescent backup/restore and offline inspection exist; the production operations, upgrade, packaging, migration, observability, and soak package is largely outstanding. |

## Gate integrity notes

- `JournalPosition`, local `Lsn`, transaction ID, and `CommitSequence` are
  distinct units. A frontier is published only as a gap-free prefix.
- Local page writeback is gated by the local WAL durable end, not by journal
  acceptance or a future remote quorum frontier.
- SQL-visible state is read from materialized relational structures. The
  journal selects recoverable history; it is not queried to hide apply lag.
- G0 requires real numeric allocation, copy, queue, latency, and recovery
  budgets recorded on the declared physical reference host.
- G1 requires a generated crash-point registry and fail-closed evidence for
  unrecoverable corruption, not an informal claim that every boundary was
  exercised.
- R2 production integration remains hard-gated on G4. Early consensus work is
  contract research and deterministic simulation only.
