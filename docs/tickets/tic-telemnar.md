---
id: tic-telemnar
status: closed
type: story
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: evidence
evidence:
    - docs/tickets/tic-telemnar.md
tags:
    - performance
links:
    - tic-da4e
created: 2026-09-13T11:46:48.638206Z
---
# Avoid reverse-reference discovery for unchanged referenced keys

### Outcome and admission

Eliminate avoidable discovery before the existing unchanged-key fast path; do not claim to add an unchanged-key check that already exists.

This is a downstream mechanism candidate, not an instruction to implement before
its dependencies deliver affirmative evidence. At handover, the lead records the
exact affected path, measured cost, resource/lifetime contract, and expected
mechanism change in this ticket. If the cost is absent or the mechanism already
exists, record a no-change disposition and revise dependent promotion scope;
never invent work or mark undelivered code as shipped.

### Owner and bounded scope

`RelationalDescriptorReferenceCheck.changed` already avoids probes for unchanged encoded keys. The discovery/enforcement owners decide whether that same validated equality can safely prevent `ForeignKeyChecks.scan` enumeration earlier.

Trace necessary metadata first. Reuse canonical equality and conservative fallback when dependencies cannot be proven unchanged. No duplicate comparison/null policy. This candidate does not depend on the reverse-FK index unless the admitted design demonstrates a real prerequisite and records that edge.

### Source handover, 2026-09-13

Static inspection at local integration `27033027` identifies a smaller first
implementation than moving or duplicating the reference-key encoder. In
`RelationalDescriptorTableAccess.prepareUpdate`, the current tuple delta plan is
already successfully prepared before `foreignKeyChecks.checkUpdate` opens the
NAME_MAP_SPACE catalog scan. `RelationalDescriptorTupleDeltaPreparation` encodes
every primary/secondary physical key before and after using the same logical row
ID, compares the canonical bytes, and shares offsets only when equal.
`RelationalDescriptorTupleDeltaPlan.changedAt` exposes that existing result.

Use an unchanged complete physical-key set as the conservative sufficient proof
for skipping inbound reference discovery. This is a superset of all targets that
`RelationalDescriptorReferenceCheck.physicalKey` can resolve, including secondary
keys. Reuse the successful current UPDATE plan belonging to the exact descriptor;
do not infer equality from changed SQL column names, mutation totals alone, a
prior row's plan or a cache of child descriptors. A changed key follows the
existing scan and per-reference checks. Do not add another byte comparator,
encoder pass, null rule, reverse index or retained state for this decision.

Keep row validation, resource preflight, tuple protection, uniqueness and
outbound foreign-key validation in their existing order. A self-referencing
table still needs outbound validation even when its own referenced keys are
unchanged. DELETE and DROP retain their existing discovery/enforcement paths.
Failed preparation cannot authorize a skip; subsequent rows and savepoint reuse
must consume a newly prepared plan. Review schema-pin/DDL serialization before
accepting removal of the catalog scan's incidental read dependencies.
Cover reset/failed plans and tables without physical keys explicitly. Preserve
row/key validation, foreign-key outcomes and failures on the fallback path;
skipping discovery intentionally removes reads of unrelated catalog rows, so
global equivalence of incidental catalog I/O/resource/corruption statuses is not
an acceptance claim. Record the reviewed status-order boundary before coding.

The first mechanism prediction is zero inbound catalog discovery for updates
that leave every physical index key unchanged; changed-index updates retain the
current discovery count. This deliberately makes no claim of avoiding discovery
when only an unreferenced index changes. Count catalog discovery separately from
child row probes, and separate remaining outbound-FK work. Existing tuple-plan,
SqlCompositeForeignKeyTest and EmbeddedRiverForeignKeyTest coverage supplies
starting points for focused proofs, with private/concurrent DDL and savepoint
cases added at their existing owners. Use a test-local observation seam; do not
introduce permanent profiling infrastructure for one counter.

Focused existing seams include tuple-plan unchanged-byte sharing and failed-plan
scrubbing; RelationalDescriptorRowPathTest's changed-key staging and rolled-back
private-pin cases; RelationalSchemaGateTest's second-transaction exclusion;
RelationalDescriptorBindingTest's savepoint name restoration; and
SqlCompositeForeignKeyTest's composite/null, self-reference and child-insert/
parent-delete cases. These are starting points, not claims that this new skip is
already covered or that any new validation ran during this source review.

This source handover does not close tic-da4e, remove its dependency or claim an
implementation. Its recorded FK stack samples include more than the avoidable
portion and cannot predict a TPS gain. The lead must commit the reviewed narrow
admission/disposition from that investigation and use the latest pushed stable
checkpoint before claiming this code slice. The bitmap checkpoint is currently
local and awaiting publication.

Independent adversarial reviewer `telemnar_handover_review` approved this handover
on 2026-09-13 after narrowing the acceptance condition and incidental-status
scope. This approves the documentation only: schema/DDL safety and measured
discovery counts still need implementation proof; no code, performance gain or
dependency completion was approved.

### Acceptance and adversarial tests

Prove updates leaving every physical index key unchanged avoid inbound catalog
enumeration; any changed physical key retains discovery and enforcement. Cover
composite/nullable keys, type/collation representations, self-references, multiple
referenced candidate keys, concurrent/private schema changes, savepoint rollback
and changed non-key columns. Reject stale plan reuse after a descriptor successor
or savepoint transition. Count discovery and row probes separately; preserve
admitted row/key validation, foreign-key decisions, fallback failure propagation
and rollback. Removed discovery does not perform incidental unrelated catalog
I/O/error checks, as scoped above.

Apply the shared execution/promotion contract in
[the handover](../plans/performance-three-epics-handover.md). The story owns one
coherent merge/rollback boundary with all River-owned callers migrated and the
superseded path removed. Profiled runs prove the mechanism; separate matched
uninstrumented runs establish performance. No per-row allocation, unbounded
retention, extra execution path, weakened isolation/durability, or benchmark-family
policy may be introduced. A negative result is a documented rejection, not a
performance delivery. Independent review is required before promotion.

### Reviewed narrow admission, 2026-09-14

Independent relational/DDL reviewer `execution_admission_review` approves this
one-mechanism candidate from retained tic-da4e evidence: 40/1,185 inclusive
FK-discovery samples, 38 through checkUpdate. The current physical-key and schema
owners are unchanged. Broader wait attribution is not this mechanism's admission
prerequisite; tic-da4e becomes a link, as for tic-twofoot, and remains in progress.
No avoidable-cost fraction or throughput gain is inferred from these samples.

The maximum production change is one UPDATE discovery predicate using the fresh
complete tuple plan, at the existing tuple/FK owners. The skip requires an UPDATE
plan for the identical descriptor and no changed physical key. A successful
zero-key descriptor qualifies; reset, failed, non-UPDATE and mismatched plans do
not. No metadata cache, comparator, retained equality, protocol, lock or storage
mechanism is admitted. All earlier row/key/preflight/protection statuses and all
changed-key fallback statuses remain authoritative; incidental unrelated catalog
reads and their errors are intentionally removed on the admitted skip path.

SchemaGate independently excludes concurrent DDL; private-pin admission remains
with DescriptorPin/DescriptorSession and its savepoint visibility owner. Focused
proof must cover those boundaries and distinguish discovery from row probes.
Stop and reject the optimization if physical-key coverage or schema ownership
is disproved, the discovery denominator does not move, or matched measurements
show no useful benefit or an unexplained repeated regression.

The published starting checkpoint is 27033027,
`perf-checkpoint-20260913-result-bitmap`. Admission review is not implementation
or performance approval.

### Measured rejection, 2026-09-14

Rejected; no production change is integrated. Candidate 45c694cc on the pushed
branch ticket/tic-telemnar-unchanged-discovery implements only the admitted
fresh-plan predicate in three production classes. Its 41 focused tests pass,
including exact discovery/probe counts, zero-allocation plan inspection, reset,
failed/mismatched plans, composite/null and self references, private index and
savepoint reuse. Independent execution_admission_review accepts the source
correctness and unchanged schema ownership. Slopmark is unchanged (TableAccess
26.7219; the other two production classes zero).

The separate performance gate rejects retention. Matched sample/all, four
workers, one warehouse, seed42, retries20, 5s warmup/30s measurement produced
control 573.28/561.37 TPS (p99 31.752/32.735 ms) and candidate 561.94/547.93
(p99 32.621/32.997 ms). A predeclared 120s C/A/A/C sequence yielded
553.57/450.40/452.78/438.00 TPS, with p99 32.915/40.665/41.091/41.746 ms.
The final control also slowed: these data establish neither a candidate-caused
regression nor its host cause, and establish no repeatable useful benefit.
Do not widen the optimization or repeat samples solely to retain it.

All eight runs passed invariants, attempt accounting, zero failed/unknown
outcomes, graceful server stop and owned-data removal. The frozen control is
the accepted 27033027 production runtime; only three class files differ in the
candidate. GraalVM25.0.4+7.1, heap1GiB, READ COMMITTED with explicit FOR UPDATE,
loopback TCP/TLS1.3 and local durable-WAL acknowledgement are unchanged.
Commands, individual logs, source identity and all report paths are retained in
/private/tmp/river-performance-20260914 (control/candidate logs, long-samples.log
and telemnar-long-plan.txt). The durable evidence location is recorded in the
shared checkpoint ledger. No clean full-build checkpoint or performance tag is
issued for this rejected code. Independent execution_admission_review concurs.
