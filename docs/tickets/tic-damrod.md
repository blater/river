---
id: tic-damrod
status: closed
type: story
priority: 1
assignee: blater
delivery: code
base-commit: 4ac490321a9f1110900d34562d671a6709af8fc5
branch: ticket/tic-damrod-dry-refactoring
delivered-commit: 496656700a2a06b5331481076b25c7a0f5535d30
checkpoint-tag: perf-checkpoint-20261001-tic-damrod-dry
tags:
    - refactor
    - dry
    - maintainability
links:
    - tic-curufin
created: 2026-10-01T14:47:06.64208Z
---
# Consolidate cataloged DRY responsibilities into concise, coherent owners

## Description

Consolidate the duplicated responsibilities in the 2026-10-01
[WET catalog](../wet.md) into concise, coherent code. Shared policy should have
one clear owner, callers should express their specific work directly, and the
superseded implementation should disappear in the same slice.

This is structural refactoring with no assumed throughput gain. The observable
outcome is fewer independent implementations of the same responsibility and
simpler affected call paths, with existing behavior verified through real
consumers. Follow [AGENTS.md](../../AGENTS.md) and
[the manifesto](../../manifesto.md) rather than introducing another policy.

### Scope and sequence

The scope is fixed to WET-01 through WET-18. Refresh each finding against the
implementation base before editing; catalog suggestions are candidates, not
proof that superficially similar contracts are interchangeable. Implement P1
first, then P2, then P3. Each slice changes one listed responsibility and its
necessary River-owned callers and tests together. WET-07 and WET-08 may share
one slice if the same ordering/name-resolution owner requires it.

| Priority | Catalog entries | Intended consolidation |
| --- | --- | --- |
| P1 | WET-01 | Use `FormatBytes` for repeated fixed-width durable primitives. |
| P1 | WET-02 | Use one SQL comparison operator-to-truth mapping. |
| P1 | WET-03 | Give exact-numeric precision and scale rules one type-policy owner. |
| P1 | WET-04 | Share relational tuple-key equality and physical-key lookup. |
| P1 | WET-05 | Share descriptor DDL completion, pin release, and result publication. |
| P1 | WET-06 | Share canonical numeric text parsing; keep record-specific admission at its boundary. |
| P1 | WET-07, WET-08 | Make name-comparison contracts explicit and consolidate overlapping ordering policy; remove a path only after verifying that it is superseded. |
| P2 | WET-09 | Complete the existing JDBC metadata result-state owner where contracts match. |
| P2 | WET-10 | Consolidate stable lock slot mechanics without obscuring typed storage. |
| P2 | WET-11 | Share response capacity calculation where client/server admission contracts match. |
| P2 | WET-12 | Simplify common result-value access while keeping row/command lifetimes explicit. |
| P2 | WET-13 | Share catalog status-detail stamping, preserving failure and cleanup ordering. |
| P2 | WET-14, WET-15, WET-16 | Reuse cohesive storage fixtures, SQL assertions, and embedded query lifecycle assertions. |
| P3 | WET-17 | Share genuinely common paged-array allocation policy while retaining primitive storage. |
| P3 | WET-18 | Share benchmark counter saturation arithmetic. |

Source locations and duplication evidence remain in the catalog; do not copy
another source inventory into this ticket. Update the affected catalog entry
with its disposition and implementation reference as each slice lands.

## Design

- Prefer an existing owner and direct delegation. Add a small local helper only
  when it gives a real shared responsibility a clearer home. Remove redundant
  forwarding, state, branches, imports, and obsolete tests with the replacement.
- Judge concision across the owner and all callers. Moving repeated bodies into
  many one-line wrappers, introducing a configurable framework, or spreading
  one operation across more objects does not satisfy this ticket.
- Preserve deliberate differences in case handling, null semantics, numeric
  domains, admission, lifecycle, and failure precedence. Share the common rule;
  keep consumer-specific work local. Trace live consumers before removing a
  class described as legacy.
- Keep primitive storage, buffer ownership, allocation/copy behavior, and
  release ordering explicit. Do not add per-row allocation, captured callbacks,
  boxing, or generic containers to simplify a hot path.
- The maximum change shape per slice is one cataloged responsibility and its
  necessary callers/tests, except the coupled WET-07/WET-08 case above. This
  ticket owns no new executor, queue, retry loop, value representation, format,
  protocol, SQL feature, diagnostics framework, or dependency-graph redesign.
- Stop a proposed extraction if it adds indirection without simplifying the
  complete responsibility or conflates distinct contracts. Record the evidence
  for retaining a local implementation. A discovered defect or required
  architectural redesign becomes a separate prerequisite; discovery does not
  expand this ticket.

## Acceptance Criteria

- [x] Every WET-01 through WET-18 entry has a reviewed disposition: consolidated
  with an implementation reference, already resolved on the implementation
  base, or retained with concrete evidence of distinct contracts or increased
  complexity from extraction. Deferred work has a linked ticket and prevents
  claiming the catalog fully resolved.
- [x] Each implemented responsibility has one clear policy owner. All affected
  River-owned callers use it; duplicate implementations, transitional adapters,
  dead state, and superseded tests are removed in the same slice.
- [x] Review confirms that the affected code is more concise and easier to
  follow as a whole. Avoid arbitrary line-count, source-token, or score targets;
  explain the removed duplication and any necessary new abstraction briefly.
- [x] Focused tests exercise the shared owner through materially different
  callers, including relevant failure and cleanup boundaries. Affected-module
  checks pass with `--no-daemon`; required public behavior, durable bytes,
  SQL semantics, status precedence, and ownership remain covered.
- [x] Durable, recovery, concurrency, and security changes receive independent
  review appropriate to their boundary. Hot-path changes retain allocation/copy
  expectations and use the proportionate measurement and slopmark checks in
  AGENTS.md. Expand validation only for a concrete concern; no new benchmark
  campaign or performance claim is part of this ticket.
- [x] The catalog records final dispositions and the ticket records immutable
  delivery commits before closure under the existing ticket delivery policy.

## Notes

### 2026-10-01T15:35:06Z

**Delivery — 2026-10-01**

Implemented on `ticket/tic-damrod-dry-refactoring` from
`4ac490321a9f1110900d34562d671a6709af8fc5`. Sixteen cohesive code commits span
`2a2ec986d1b7490833ee3102eea4f3fadc58183b` through
`3646b75d43fc1eba087bd356e31f0b073929d820`; each carries `Ticket: tic-damrod`.
The catalog maps each responsibility to its delivery commit. Its WET-08 and
WET-17 retention decisions reflect live, distinct contracts. WET-09 and WET-12
share matching operations while retaining different lifecycles. Optional wider
migration decisions are recorded in [tic-curufin](tic-curufin.md).

The new owners replace the duplicate bodies directly. There are no transitional
adapters, new formats, result representations, executors, or dependency edges.
Lock chunks retain the same primitive arrays and budget charges. Durable
primitives retain absolute access and byte order. Existing warmed public-result,
SQL/transaction allocation and resource-pressure tests remain part of the
passing affected-module suite.

**Validation**

- Focused tests cover independent durable byte expectations through all four
  codecs, operator truth at signed extrema, canonical parser admission, response
  bounds, all four lock stores across occupancy words/chunks and generation
  rollback/reuse, and text-copy rejection before destination mutation through
  both public wrappers. Existing relational, DDL, JDBC, daemon, allocation,
  pressure, and recovery tests pass.
- All affected checks pass with `--no-daemon`: base, format, storage, engine,
  tx, engine-api, protocol, client, server, server-app, JDBC, and bench. The
  complete affected run took 3m 28s. Final tx/API/base checks and final
  protocol/client/server checks pass after additional boundary coverage and the
  response-owner refinement.
- `verifyModuleGraph`, `verifyProjectDependencyVisibility`,
  `verifyIndexedTableClassReferences`, and `verifySqlRuntimeInvocationPolicy`
  pass. `verifySourcePolicy` passes against a snapshot of tracked/current
  unignored sources at `/private/tmp/tic-damrod-policy-checkout`. In the main
  checkout it also scans two pre-existing ignored benchmark XML artifacts with
  tabs; those artifacts are untouched. The raw Unicode escape in the new parser
  test was corrected before the passing snapshot check. `git diff --check`
  passes.
- Repository-wide `tk validate` reports 173 pre-existing issues in older
  tickets; neither tic-damrod nor tic-curufin has a reported issue. All commits
  in `4ac49032..3646b75d` have the required exact tic-damrod trailer. Closure
  succeeded with the immutable delivered code commit recorded above.
- One standalone authenticated JDBC smoke passed on OpenJDK 27 / macOS arm64:
  `tools/tps-test.sh --version=tic-damrod-structural --mix=new-order
  --profile=tiny --terminals=1 --warmup-seconds=1 --measured-seconds=3 --seed=42
  --output-dir=/private/tmp/tic-damrod-boundary-smoke`. It reported status OK,
  pre/post invariants passed, 1,548 measured commits, 16 expected rollbacks,
  zero errors/retries, and no metric overflow; owned process/database cleanup
  completed. This is a correctness smoke, not comparative performance evidence.
  Logs and artifact are outside Git at the named path. The smoke used the exact
  delivered code before the slice commits were recorded.

**Independent review and ownership check**

Read-only reviewer Avicenna independently approved durable access, exact numeric
reachability, SQL comparison/names, relational equality/lookup, all six DDL
lifecycles, canonical parser admission/credential cleanup, JDBC extraction,
lock metadata/generation accounting, response leases/failure restoration,
public copy admission, catalog status precedence, test fixtures, and metrics
arithmetic. Review included the final helper move and added boundary tests;
reviewer performed no edits or builds. Tests were run by the integrator.

Slopmark before/after inspection was used as a responsibility check, not a
quality or performance gate. Representative touched scores: Columns result set
145.123 → 119.416; metadata base 15.587 → 23.790; TpccMetrics
117.584 → 109.487; descriptor index creation 21.856 → 17.420;
LockTypedSlots 15.364 → 17.496. New owner increases were reviewed against removed
caller bodies. Tool boundary coverage is partial. Adding capacity selection to
ProtocolFrameCodec raised its score from 41.062 to 117.476 and prompted a stop
and ownership review. The unchanged calculation moved to the concrete
`ProtocolResponseCapacity` owner (3.410); the codec returns to 41.062. Both
immediate consumers delegate directly. This change keeps encoding/decoding and
response-capacity policy coherent without introducing a configurable framework.

### 2026-10-01T20:38:13Z

**Integration and post-merge smoke — 2026-10-01**

Integrated into the repository default branch `master` at
`496656700a2a06b5331481076b25c7a0f5535d30` with an explicit merge commit and
`Ticket: tic-damrod`. The remote has no `main` branch. The merged tree matches
the independently reviewed delivery exactly. Annotated checkpoint:
`perf-checkpoint-20261001-tic-damrod-dry`.

Refreshed `:river-bench:installTps` with `--no-daemon`; the exact merged source
passed `tools/tps-test.sh --version=tic-damrod-merged-49665670 --mix=new-order
--profile=tiny --terminals=1 --warmup-seconds=1 --measured-seconds=3 --seed=42
--output-dir=/private/tmp/tic-damrod-post-merge-smoke`.
Status OK; pre/post invariants passed; 2,118 measured commits, 25 expected
rollbacks, zero errors/retries, zero active transactions/locks/waiters, and
completed owned cleanup. This is a correctness smoke without a performance
claim. The checkpoint registry links this evidence. A short integration lease
covers merge, smoke, and push; publication includes master, the delivery branch,
and the annotated tag.
