# tic-4d14 initial lock-scope audit

Source: `f51342a7b5e0e6030bd2856264c3c379c9a4d96d`
(`perf-checkpoint-20260915-force-overlap`).

Status: **acceptance blocked by mandatory attribution evidence**. The accepted
`tic-f1bb` artifacts and earlier retained lock audits reconcile the material
retained-lock classes, separate New Order from Payment footprint, and show
where block events occur. They do not classify holdings by logical step, split
blocked time by class, or attribute acquisition/release service cost to a
scope or rule. Those are explicit `tic-4d14` outcome and stop-condition gates.
`tic-845d` is therefore not implementation-ready.

## Evidence and reconciliation

This audit uses these accepted 30-second candidate metrics:

- `/private/tmp/river-tic-f1bb-evidence/candidate-long-1-live/server-metrics.log`
- `/private/tmp/river-tic-f1bb-evidence/candidate-long-2-live/server-metrics.log`
- `/private/tmp/river-tic-f1bb-evidence/candidate-long-3-live/server-metrics.log`

They used the same four-terminal, one-warehouse configuration. Generic lock-block
causality is valid in all three runs: no unclassified bucket and no overflow.
The deadlock-detail budget was zero, so these artifacts do not map locks to an
opaque workload step.

Across 74,063 successful write transactions in 74,011 successful group cohorts,
the release telemetry reports 7,051,291 retained holdings, or 95.20 per write.
Every reported material class reconciles exactly to that total:

| Scope | Holdings | Share | Per write | Block events | Required role / audit result |
|---|---:|---:|---:|---:|---|
| `KEY` | 3,959,167 | 56.15% | 53.46 | 20 | Exact scalar read/write protection and index-root lifecycle protection. Required as a class; identical requests are already coalesced into one retained holding. |
| `RANGE` | 74,063 | 1.05% | 1.00 | 0 | Serializable scalar predicate/phantom protection. No removal candidate. |
| `TUPLE_KEY` | 1,964,563 | 27.86% | 26.53 | 34,602 | Exact tuple uniqueness, foreign-key, read/write, and write/write conflict protection. Required as a class. |
| `TUPLE_RANGE` | 1,053,498 | 14.94% | 14.22 | 57,228 | Serializable tuple-prefix/predicate phantom protection. Required as a class. |
| **Total** | **7,051,291** | **100%** | **95.20** | **91,850** | Exact reconciliation. |

`ROW` and `SCHEMA` are zero in the successful group-release records and are
not material classes for this measured write path. The evidence does not cover
every read-only, direct, failure, or cancellation release path, so it cannot
support a repository-wide all-outcome holding claim.

Block events also reconcile exactly. `KEY` has 20 `ACTIVE_OWNER` events;
`RANGE` has none. `TUPLE_KEY` has 7,438 `ACTIVE_OWNER` and 27,164
`FIFO_FAIRNESS` events. `TUPLE_RANGE` has 27,415 `ACTIVE_OWNER` and 29,813
`FIFO_FAIRNESS` events. Thus tuple ranges account for 62.31% of block events
and tuple keys for 37.67%. FIFO waiting preserves the canonical queue policy;
it is not evidence of a duplicate lock rule. Active-owner events identify a
real incompatible owner, not redundancy.

The same runs record 202,081,736,421 blocked nanoseconds over 91,850 events,
an aggregate mean of about 2.20 ms. They do not split that time by scope,
resource, acquisition rule, tag, or step. Release telemetry records
6,379,518,981 ns in `GROUP_LOCK_RELEASE` across 74,011 cohorts (about 86.2 us
per cohort), including 6,304,612,573 ns in holding release (about 85.2 us per
cohort, or 894 ns per released holding as aggregate wall time) and 20,800,265
ns in record recycle. Those timings cannot attribute service cost to a scope or
rule and must not be treated as CPU cost per acquisition.

Existing retained audit evidence predating `tic-f1bb` supplies useful family
attribution but does not fill those gaps. The accepted
[`docs/perf_review.md`](../../perf_review.md)
recorded two standard runs at 78.7 and 79.0 holdings per write. Its
single-terminal New Order probe released 43,098 holdings for 336 writes (128.3
per write: 18,510 `KEY`, 336 `RANGE`, 16,168 `TUPLE_KEY`, and 8,084
`TUPLE_RANGE`) and spent 91,660,124 ns in aggregate holding release. Its
recorded artifact root is `/private/tmp/river-p1-lock-footprint-new-order`. The
Payment probe released 11,295 holdings for 509 writes (22.2 per write: 6,411
`KEY`, 509 `RANGE`, 2,545 `TUPLE_KEY`, and 1,830 `TUPLE_RANGE`) and spent
18,867,375 ns in aggregate holding release; its recorded artifact root is
`/private/tmp/river-p1-lock-footprint-payment`. Both recorded zero waits,
retries, and errors. The recorded
`/private/tmp/river-p1-lock-release-split-current` split attributed 486,244,016
of 491,673,158 ns of lock release to canonical holding removal, while the
accepted `/private/tmp/river-batched-lock-release.U3GReO` scheduler experiment
removed repeated drain work but did not change the holding policy
or produce a consistent normalized release-cost improvement. These records
show that holding cleanup is material and New Order owns the larger footprint;
they still contain no per-step holding reconciliation or per-scope/rule
service timing.

## Canonical policy assessment

The transaction layer remains the sole owner of serializable lock policy.
[`LockScope`](../../../river-tx-api/src/main/java/io/riverdb/tx/api/lock/LockScope.java#L4)
defines the measured exact and interval scopes.
[`LockResourceOverlap`](../../../river-tx/src/main/java/io/riverdb/tx/LockResourceOverlap.java#L14)
validates exact scalar identities and half-open scalar ranges and applies their
overlap rules at lines 52-98. Tuple keys use exact tuple identity and tuple
ranges protect tuple intervals and prefixes.

[`IndexedTransactionScanCoordinator`](../../../river-engine/src/main/java/io/riverdb/engine/table/IndexedTransactionScanCoordinator.java#L33)
acquires a serializable scalar `RANGE` before snapshot selection.
[`IndexedTransactionTupleScans`](../../../river-engine/src/main/java/io/riverdb/engine/table/IndexedTransactionTupleScans.java#L25)
protects the index-root lifecycle and acquires the serializable tuple range
before selecting its snapshot. Tuple reads and mutations use the exact-key
path in
[`IndexedTupleKeyProtection`](../../../river-engine/src/main/java/io/riverdb/engine/table/IndexedTupleKeyProtection.java#L26),
which first protects the index root and then acquires the `TUPLE_KEY` needed
for exact-key conflicts, uniqueness, and foreign keys.

The current group lifecycle releases locks during publication, before force
and before final transaction outcome:
[`TransactionCompletion.publishCommittedGroup`](../../../river-tx/src/main/java/io/riverdb/tx/TransactionCompletion.java#L170)
calls `locks.lifecycle.complete` at lines 178-185, and
[`IndexedGroupCommitBatch.publishPrepared`](../../../river-engine/src/main/java/io/riverdb/engine/table/IndexedGroupCommitBatch.java#L182)
records those releases as publication work at lines 209-250. Pending durability
then retains the group requests, transaction lease/result handles, commit
sequences, required WAL end, and page-generation chain identity in
[`IndexedDurabilityCohortRing`](../../../river-engine/src/main/java/io/riverdb/engine/table/IndexedDurabilityCohortRing.java#L5).
After successful force, the coordinator releases the durability page chain and
completes the published transactions at
[`IndexedGroupCommitCoordinator`](../../../river-engine/src/main/java/io/riverdb/engine/table/IndexedGroupCommitCoordinator.java#L381).
Those later pins and leases are not lock holdings.

The shared index-root `KEY` obtained before tuple-key protection is not a
duplicate-holding candidate.
[`LockExactTable.tryAcquire`](../../../river-tx/src/main/java/io/riverdb/tx/LockExactTable.java#L58)
resolves the existing transaction/resource holding at lines 70-76, and
[`LockExactHoldingLifecycle.acquire`](../../../river-tx/src/main/java/io/riverdb/tx/LockExactHoldingLifecycle.java#L13)
issues another reference to it. `retain` at lines 68-81 releases that extra
reference when the holding is already retained.
[`IndexedLockWait.acquireRetained`](../../../river-engine/src/main/java/io/riverdb/engine/table/IndexedLockWait.java#L137)
uses exactly that acquire-then-retain path. Therefore repeated identical root
requests reuse one canonical
holding rather than adding entries to the release denominator. The same
resource-identity fast path applies to exact repeated requests generally.

No existing evidence package identifies another redundant or unnecessarily
retained rule. The numerous tuple classes carry the observed contention and
protect exact-key anomalies or range/prefix phantoms. The scalar range is one
per measured write, has no observed blocks, and protects scalar scan phantoms.
The remaining `KEY` population mixes required scalar exact protection with
coalesced index-root lifecycle protection and has only 20 observed block
events. Raw counts do not justify weakening any of these rules.

No lock rule is selected for removal. This is a no-proven-candidate conclusion
from all located retained evidence and the canonical sufficient-holding path,
rather than an accepted `tic-4d14` closure: the ticket's mandatory per-step,
blocked-time, and service-cost classification remains absent.

## Exact evidence missing for acceptance

The remaining gaps are narrower than a new all-outcome telemetry campaign:

1. The existing benchmark/session step tags are not joined to holding counts,
   so the material holdings cannot be reconciled by logical step as required.
2. Existing block buckets reconcile event counts by scope and grant cause, but
   expose only one global blocked-nanosecond total. They cannot rank the
   classes by successful blocked time.
3. Existing timers attribute aggregate holding-removal service to a family or
   cohort, but expose neither acquisition service nor release service by scope
   or rule. They cannot predict a candidate's service-cost effect.

No production change, test change, workload, instrumentation, or lock policy is
proposed by this read-only audit. The ticket's three explicit attribution gates
remain.
