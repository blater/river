---
id: tic-186e
status: closed
type: bug
assignee: blater
delivery: code
base-commit: 7df1dc6a3fd8c5747f16761261e4e31c2b3087a4
branch: ticket/tic-186e-catalog-transaction-resolution
delivered-commit: db1059a08257d35de9b1b7bc7ac72225d6225da1
checkpoint-tag: perf-checkpoint-20260906-catalog-transaction-resolution
evidence:
    - /private/tmp/river-tic-186e-evidence/promotion-smoke
    - docs/performance-checkpoints.md
tags:
    - performance
    - catalog
    - transactions
created: 2026-09-06T12:37:55.670297Z
---
# Resolve internal catalog descriptors in the owning transaction

## Evidence

- Stable production base: pushed `perf-checkpoint-20260906-page-generation-reuse`
  (`f7ff998`); branch base `7df1dc6` adds only the accepted closure documentation.
- Evidence root: `/private/tmp/river-tic-186e-evidence`.
- `reproducer.xml` fails on unchanged production because the successor cannot
  reach enqueue while force is held. The fixed test passes both success and
  injected force failure, with real SQL program updates and foreign-key reads.
- Independent review: `/root/review_catalog_overlap`, no production blocker.
  Watch outer-transaction metadata lock residency and pending-mutation scan
  cost in the TPS evidence. No new cache-hit allocation or retained state.
- All 983 engine tests pass, including existing allocation, recovery, program,
  catalog successor and rollback coverage. The expanded held-force test also
  checks cold-cache assembly, exact cache hits, failed-lookup cleanup and the
  standalone durable catalog API. See `engine-test-results` and
  `held-force-candidate.xml` in the evidence root.
- Slopmark touched-file scores: opener `7.42713 -> 0`; names `17.9248`,
  lifecycle `13.7851` and services `0` unchanged. Bytecode inspection retains
  only the three existing constructor allocation sites in the opener.
- Complete sample, clean-gate, baseline-policy and read-tail evidence is in
  [performance checkpoints](../performance-checkpoints.md). Short TPS:
  controls 114.600/116.800, candidate 143.200/143.500. Longer alternating pairs:
  controls 128.467/128.533, candidate 160.400/160.767, zero errors. One control
  deadlock retry reconciles; candidates have zero retries. Order Status p95
  doubles from 8.388 to 16.777 ms; tagged traces attribute slow reads to repeated
  public durability waits under the more continuously active writer. Independent
  review accepts the scoped fix with that explicit tradeoff. Diagnostic probes
  are removed, and public durability remains intact.

## Delivery

Feature `4e871da` was merged as `db1059a` and atomically pushed with its branch
and annotated `perf-checkpoint-20260906-catalog-transaction-resolution` tag.
The exact merged revision passed the real-path promotion smoke with zero
retries/errors, passed invariants and a successful terminal receipt. Closure
references that pushed integration and its evidence; the documented baseline
policy failures and Order Status latency tradeoff remain explicit.

## Design

Scope lock: internal authoritative descriptor reads use their admitted
relational transaction and existing response/commit fence. Preserve standalone
catalog durability. No Payment, cohort scheduling, WAL format, new cache or
telemetry. Stop if schema admission or cache publication cannot preserve
authoritative identities. Maximum shape: catalog opener/lifecycle, relational
bridge, focused tests and evidence.

The admitted relational caller resolves private DDL overlays first. Catalog
resolution reads the committed head/manifest and pins the exact schema identity;
it does not begin or finish the caller transaction. Schema admission prevents
concurrent relational DDL, and cache identity includes object, schema, row
layout and generation. Shared lifecycle synchronization continues to protect
the reusable loader and reservation state. Standalone opens retain an independent
durable read transaction and finish it before cache publication.

## Acceptance Criteria

Held-force proof: successor resolves descriptors and reaches enqueue before
predecessor force, without early response; force failure fences dependents.
Cache miss/hit and DDL safety, focused/module tests, clean full test, independent
review, two matched tps-test baseline/candidate samples and slopmark before/after.
