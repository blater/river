---
id: tic-af29
status: in_progress
type: story
assignee: blater
parent: tic-5db4
delivery: code
base-commit: 5d70625
branch: ticket/tic-af29-lock-block-causality-delivery
tags:
    - performance
    - tpcc
    - p0
    - locks
    - observability
deps:
    - tic-5cc0
created: 2026-09-04T20:32:35.538355Z
---
# Classify successful lock blocking for P0

Expose the causal lock-block evidence required to explain the detected standard-mix 10:2 scaling regression before changing lock policy.

## Outcome

A bounded generic aggregate identifies which exact scheduler grant
preconditions caused successful lock blocks, and every aggregate block and
disposition reconciles without changing scheduler behavior. `tic-1dda`, not
this ticket, owns collection of the matched P0 workload evidence.

## In Scope / Owning Mechanism

The transaction scheduler's canonical grant predicate owns classification at
the point a request is actually blocked. One phase-scoped aggregate snapshot
exports the result through the existing diagnostics boundary.

## Non-goals

- Measure retained transaction snapshots; `tic-8e74` owns that cleanup gauge.
- Execute or interpret the two- and ten-terminal matrix, change lock policy, or
  claim a throughput improvement.
- Add TPC-C concepts to `river-tx`, a second grant predicate, an unbounded event
  stream, or a general metrics framework.

## Stop Conditions

Stop if classification would duplicate or approximate the scheduler predicate,
if disabled classification adds a clock read or allocation, or if the aggregate cannot
reconcile exactly with actual blocks and terminal dispositions. If the scoped
dimensions cannot distinguish the dominant block class, retain that result and
open a separately reviewed diagnostic ticket; do not add dimensions in flight.

## Maximum Change Shape

One canonical predicate owner and one explicitly bounded aggregate structure
may change, plus the existing cold diagnostics adapter and focused tests.
Scheduler admission, fairness, lock lifetime, and transaction control flow must
remain equivalent in outcome; no parallel scheduler, classifier, or diagnostics
store is permitted.

## Design

Add bounded, allocation-stable, generic, phase-scoped aggregate classification
for every actual block by resource scope, requested mode, held or blocker mode,
ordinary or conversion or FIFO queue relationship, and the exact enforced
scheduler grant predicate. Scheduler admission and diagnostics share the
canonical predicate owner. Reconcile actual blocks, grants, timeouts,
cancellations, victims, and every bucket without TPC-C types in `river-tx`.
No detailed event stream is added. Disabled classification adds no clock reads
or steady-state allocations.

## Acceptance Criteria

Focused active-owner, FIFO-fairness, and conversion-priority tests prove exact
bucket selection, grant-predicate identity, overflow rejection, phase
separation, successful handoff, cancellation/victim separation, and zero
terminal transactions, locks, and waiters. Aggregate buckets sum exactly to
actual blocks and dispositions. Capture-disabled validation retains the existing lock-path allocation bound,
checks that classification adds no allocation or clock-read sites, and proves
unchanged grant outcomes. The
cold output contract supplies every declared dimension and reconciliation
total to `tic-1dda`; aggregate TPS alone admits no lock optimization.

## Notes

### 2026-09-04 ten-terminal architecture priority review

This remains an immediate P0 evidence prerequisite, not a throughput fix. It
does not remove a lock block. Because the implementation touches the canonical
scheduler predicate, acceptance also needs matched capture-disabled control/candidate checks for
added steady-state allocation and throughput/latency regression. A repeated
shift triggers investigation; the working agreement permits required
observability with an explicitly inconclusive performance decision. Enabled-capture results must be treated as
diagnostic evidence whose observer cost is reported, not as a capacity sample.

### 2026-09-07 implementation and allocation-gate reconciliation

The aggregate and grant decision are generic and phase-scoped; the snapshot
registry gauge remains solely in `tic-8e74`. Current release-drain protection
and requester-dependent fairness are preserved. Existing release-order tests
now run with capture enabled and disabled, including revoked handoffs.

The pre-existing allocation gate allows at most 512 bytes across 10,000 warmed
rounds. That bounded measurement is not literal zero-byte proof. Both capture
modes retain the same limit, resources, and 1,000-round warmup. A temporary JFR
investigation traced a repeated roughly 7.2 KB failure to deferred loading of
`TransactionGroupCompletionTimings` through existing null-timing lock cleanup.
The test now initializes that optional type during setup; the full transaction
suite passes. An exploratory exact-zero assertion observed 152 bytes; retain
that limitation rather than claiming measured zero. Classification adds no
hot-path allocation or clock-read sites. Raw probes and traces are retained in
`/private/tmp/river-tic-af29-evidence-20260907`.

Slopmark review accepts the grant owner's 101.8 score as concentrated predicate
complexity, not a numeric improvement: the scheduler scored zero before and
after. The aggregate (73.6849) and cold snapshot (76.4318) have distinct owning
responsibilities; no policy is duplicated to lower a score. Independent review
found no grant-policy divergence or lifecycle reconciliation defect.

Implementation/measured candidate: `d1460d8`, based on pushed `5d70625`
(production-identical to `perf-checkpoint-20260907-force-target-ownership`).
Clean gate: `GRADLE_USER_HOME=/private/tmp/river-gradle-tic-f8dd ./gradlew clean
test --no-fail-fast verifyHotPathBytecodeFixtures` passed 1,805 tests, zero
failures/errors, two existing skips in 7m45s. All 146 transaction tests passed.
Source/bytecode policy findings remain 259 before/after, with no additions;
indexed-reference and bytecode-fixture checks pass.

TPS evidence and acceptance are recorded in `docs/performance-checkpoints.md`.
The initial longer enabled sequence repeatedly regressed and remains part of
the evidence. Reversed longer pairs changed direction; capture-disabled pairs
also changed direction. **Performance is inconclusive, not proved unchanged.**
Independent review accepts this required `tic-1dda` observability prerequisite
under the working agreement's explicit exception. Observer cost is unquantified;
there is no speedup, capacity, or no-regression claim. This explicitly replaces
the earlier note's requirement to prove an absence of repeated regression;
it does not waive the throughput/recovery gates for `tic-f1bb`.

The control's one measured Delivery deadlock retry in the final 60-second run
reconciles exactly with one server outcome and one captured deadlock, with no
errors or retry-accounting gaps. All candidate runs have zero retries/errors.
Current TPS v2 receipts do not establish full host ownership or launched-byte
provenance; the retained manual build/source/classpath checks support diagnostic
evidence only. The corresponding prerequisite reconciliation remains open.
