---
id: tic-waymeet
status: open
type: task
priority: 1
assignee: blater
delivery: code
base-commit: 7daab25ae133e447f2c25834ff117e0549d9eca7
branch: ticket/tic-waymeet-recheck
links:
    - tic-gothmog
    - tic-bert
created: 2026-09-15T21:28:43.683539Z
---
# Use binary search for scalar B-tree internal routing

Replace BTreePage.childForKey linear separator traversal with upper-bound binary search. Preserve equality-to-right-child, signed key and namespace ordering, empty-node behavior, and existing page validation and format. No metadata cache, lock-manager, or broader index changes.

## Acceptance Criteria

Exhaustive separator/gap/edge routing tests across node occupancy and namespaces, existing split and recovery tests, independent review, affected-module and checkpoint checks, and paired New-Order samples.

## Notes

### 2026-09-15T21:30:48Z

Prepared the second bounded task from tic-gothmog follow-up. Change is confined to scalar BTreePage.childForKey upper-bound routing and focused tests. Empty/full nodes, all separators and adjacent gaps, namespace ordering, signed extremes, and equality-to-right-child behavior are covered. No tuple-tree, root-cache, lock-manager, or format changes.

### 2026-09-15T21:31:12Z

Independent correctness review approved upper-bound search, strictly ordered admitted-page invariant, all traversal callers, equality/split semantics, and read-only ownership. No findings requiring changes. Validation runs remain serialized with the tuple-key feature.

### 2026-09-15T21:36:15Z

Focused BTreePageTest and storage jar build passed in 3s. Independent review approved the strict ordering assumption already enforced at admission and unchanged namespace/equality/split behavior. Slopmark BTreePage28.3441→28.4196, with no new responsibility. Candidate binary is /private/tmp/river-two-hotpaths/routing/river and differs from stable baseline only in the storage jar. Full checkpoint and workload samples pending serialized execution.

### 2026-09-15T21:43:36Z

Clean full checkpoint passed: ./gradlew --no-daemon clean check, 3m17s, 2,029 tests reported, zero failures/errors, 19 platform/opt-in skips. Existing engine split/recovery and module-policy checks passed. Log: /private/tmp/river-two-hotpaths/routing-clean-check.log. Workload samples remain pending; only BTreePage.childForKey is changed in production.

### 2026-09-15T21:55:45Z

Performance acceptance held. Interleaved identical20s-warmup/30s sample New-Order sequence: routingA390.85TPS/serverCPU2.398ms/p994.014ms; unchanged stable control390.80/2.265/3.994; routingB388.23/2.473/4.239. All READ COMMITTED, one worker/warehouse, seed42, retries3, durable WAL, same JVM/transport. All passed with zero retries/failures/unknowns, invariants/accounting/cleanup valid. Earlier slower baselines do not establish a win against this adjacent control. Preserve candidate0a70f881 on feature branch; do not merge an unestablished performance improvement. Raw commands/captures and verified-results.json: /private/tmp/river-two-hotpaths/.

### 2026-09-15T21:56:35Z

Independent performance review agrees: no established workload benefit, and server CPU is higher in both routing samples versus the adjacent unchanged control. This is not proof that binary search intrinsically regresses; attribution remains unresolved. Keep ticket open, publish tested feature branch and docs-only outcomes, and create no production integration checkpoint tag.

### 2026-09-16T09:03:06Z

Rechecked the unchanged binary-routing mechanism on accepted tuple checkpoint 7daab25a, using fixed external harness7d91f4f (workers retained across warmup). Rebased implementation df9cb264 is on ticket/tic-waymeet-recheck; original branch and adverse evidence remain retained. Runnable variants differ only in BTreePage.class. Targeted BTreePageTest and IndexedTransactionSessionTest: 60 tests, zero failures/errors/skips, build47s; /private/tmp/river-waymeet-focused.log. Fresh unprofiled sample New-Order, READ COMMITTED, 1 worker/warehouse, seed42, retries3, warmup20s/duration30s, durable WAL/TCP-TLS, GraalVM25.0.4 -Xmx1g: controlA388.331TPS/2.275ms serverCPU/p993.953ms; routingA388.431/2.257/4.045; routingB390.178/2.227/4.012; controlB380.261/2.316/4.264. All passed, zero warmup cancellations/retries/failures/unknowns, invariants/accounting/report hashes/cleanup passed. Earlier CPU penalty did not recur: both candidate CPU figures are lower than both controls. Short-run mean CPU is2.32% lower and TPS1.30% higher, but first-pair TPS is flat and the slower final control influences the mean. Modest favorable CPU signal, no established throughput improvement; ticket remains open and routing unmerged. Individual samples, configuration and report IDs are in docs/performance-checkpoints.md under the 2026-09-16 tic-waymeet recheck; artifacts /private/tmp/river-waymeet-perf/.
