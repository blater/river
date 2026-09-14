---
id: tic-da4e
status: in_progress
type: investigation
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: evidence
tags:
    - performance
    - tpcc
    - p3
    - profiling
links:
    - tic-32b3
    - tic-telemnar
    - tic-twofoot
    - tic-eowyn
    - tic-edoras
created: 2026-09-04T15:10:07.987949Z
---
# Establish current-master mechanism baselines for the three performance epics

### Outcome

Give the lead a current, reproducible decision on repeated execution setup,
commit-force serialization and protocol work before selecting production changes.
This replaces the stale requirement to finish Payment collapse before profiling.
It consumes accepted source `5f7e6eb0` or the latest pushed stable checkpoint at
execution time, recording the exact revision; no old profile is current proof.

### Scope and owner

Runtime/performance lead with relational, WAL and protocol owners reviewing their
respective traces. Reuse the installed-server harness and existing JFR/profiling
facilities; do not build a new telemetry framework. Preserve earlier failed P0
criteria and the separately owned `tic-1dda` gate. Profiling does not certify P0.

### Deliverables and acceptance

- Reproduce sample/all at four workers, one warehouse, seed42, retries20,
  5s warmup/30s measurement as the short diagnostic anchor. Use two matched
  uninstrumented samples; lengthen and interleave controls to explain variation.
- Separately capture measured-window inclusive CPU/allocation and blocking/force,
  lock, queue, socket-write and exchange evidence. Exclude startup/load; measure
  profiler overhead; disclose missing unmounted virtual-thread waits. Report
  per-attempt and per-commit denominators and retries; do not add overlapping
  percentages or equate CPU share with an expected TPS gain.
- Trace current preparation sharing, binding-view construction, FK discovery and
  its existing unchanged-key check, scratch reset, physical writer/force handoff,
  and transport barriers. Name exact owners and distinguish already delivered work.
- Give each candidate child an implement/defer/reject decision, expected cost
  movement, workload category and focused correctness probe. Absence of a cost
  is valid evidence to reject that candidate; no speculative code requirement.
- Record actual target isolation, commit-flush contract, transport/TLS, JDK,
  resource settings and report eligibility. The old MariaDB Unix-socket versus
  River TCP/TLS run is a diagnostic, not an engine-only or parity conclusion.
  A fresh MariaDB run is needed only if this investigation uses its timing.

One evidence commit, immutable artifact paths, correctness/cleanup outcomes and
reviewed candidate decisions complete this ticket. It does not implement fixes,
produce formal cross-database claims, or depend on lifecycle certification.
Follow [the shared contract](../plans/performance-three-epics-handover.md).

### Current-source admission, 2026-09-13

Source ef935596; GraalVM25.0.4+7.1 JVM -Xmx1g. Durable evidence:
`/Users/blater/src/river-performance-evidence/20260913-da4e/` (frozen runtime,
commands, profile.py, Summary.java, JFR and summaries). External sample/all uses
four workers, one warehouse, seed42, retries20, 5s warmup/30s measurement,
READ COMMITTED with explicit FOR UPDATE and loopback TCP/TLS1.3.

Uninstrumented reports 20260913_120540_691a1b62 and
20260913_120619_948c685e passed at564.04/530.73TPS; their complete artifacts
remain below river-harness/runs. The recovered profile report
20260913_154259_7b4335e9 passed at577.51TPS and adjacent control
20260913_154338_d4132863 at562.48TPS. All report IDs have the river_harness_
prefix. Outcomes/invariants/cleanup pass and comparison keys agree.

The recovered20s recording lies wholly within measured30s, excluding load/startup.
It has1185 selected request/commit ExecutionSample+NativeMethodSample stacks,
not method CPU-time measurements; unmounted virtual waits remain unobserved.
FK discovery occurs in40 selected stacks (3.38%), binding-view preparation in1.
Weighted allocation samples attribute115,793,728bytes inclusively to
PublicResultValues.releaseHighWater; sampling weights are estimates, not exact
allocation counters. Unthrottled socket events record746,608writes and2.5407s
accumulated write duration. Do not divide these20s events by full30s transaction
counts as exact per-transaction figures. Profile timing differs+2.67% from its
adjacent control; overhead cannot be resolved within observed host variation.
The earlier temporary recording used300/s socket throttling and is excluded
from write-count evidence. Its temporary files were lost after a host disruption.

Independent Astra source/evidence review admits:

- tic-twofoot first: correct only the result bitmap retention unit mismatch;
  preserve erasure/budgets and prove warmed high-water release stops reallocating.
- tic-telemnar second: use existing reference-key equality before catalog
  enumeration, with conservative fallback. No new equality or reverse cache.
- tic-bracegirdle deferred until the smaller avoidance is measured; do not build
  a reverse catalog for cost the earlier change may already remove.
- tic-eowyn not admitted on this profile:1selected stack does not justify mutable
  binding-view lifetime/invalidation complexity. Reassess only with fresh evidence.
- tic-edoras admitted as contract/consumer investigation, not automatic pipelining.
- WAL overlap retains the independent P0 and provider contracts; sparse JFR force
  events do not prove mapped-force time or eliminate the need for held-force tests.

The separate serializable TPS test uses tiny/standard,4terminals,1warehouse,
seed42,5s/30s, server heap1GiB/client heap512MiB and unchanged explicit resource
budgets. These figures must never be compared directly with external harness TPS.
Its earlier second control disconnected during checkpoint/cleanup (tic-osgiliath);
lost temporary logs prevent precise cause attribution. A Darwin watchdog panic
report from the period exists, but does not prove the cause of that disconnect.
Fresh one-terminal1s/3s checkpoint/recovery smoke passed at601.33TPS.
A later unchanged-source four-terminal control again failed during CHECKPOINT
and server termination, with retained evidence recorded in tic-osgiliath. The
successful smoke did not establish host safety. Full retained TPS controls and
final promotion remain pending. The user subsequently reauthorized Java builds
and TPS; historical guarded short checks are recorded in
[the investigation](../plans/checkpoint-kernel-hang-20260913.md). Those checks
do not replace the missing baseline or resolve the original kernel trigger. The timeout policy was withdrawn on 2026-09-14. Shutdown correctness and
rollback/retry work do not establish the kernel cause or make failed samples acceptable.

### Retained-profile reconciliation, 2026-09-13

An offline RecordingFile pass over the retained JFR confirms events span
15:43:07.907982709Z through 15:43:27.909128792Z. The measured harness phase starts
15:43:07.554314Z and lasts 29.969956 seconds. Its full-phase accounting is 20,233
attempts = 17,308 commits + 2,846 retries + 75 expected rollbacks + four cancelled
attempts, with zero failed/unknown outcomes. These are not the recording-window
denominators; exact per-attempt/per-commit JFR ratios remain unavailable.
The reader, command arguments and event summary are retained in
`/Users/blater/src/river-performance-evidence/20260913-da4e/profile-gap-audit/`.

All 746,608 SocketWrite events belong to the four river-connection threads.
Their 143,233,658 bytes average 191.846 bytes per observed write. Stack capture
was disabled, so these events do not identify protocol message types, flush
boundaries or workload families. SocketRead uses a 1 ms threshold and 300/s
throttling; its 75 events do not measure total network waiting. Recorded parks
belong to lifecycle/scheduler threads, not attributed pending River requests.
They must not be treated as lock, queue or unmounted virtual-thread wait totals.

Five FileForce events identify river.wal on the WAL commit thread, through
NioDurableFile.forceInternal, totaling 30.381291 ms (maximum 6.825791 ms).
That owner separately forces mapped ranges and conditionally invokes channel
force for dirty metadata. The five events are not a count or duration of all
mapped WAL barriers. The current NIO provider and FK/binding owners are unchanged
between profiled ef935596 and local 27033027; this source comparison does not
turn the older timing into a new-master performance baseline.

The same recording is a Java server profile driven by the external Go harness.
SessionEndpoint.prepare appears in 29/1,185 inclusive selected samples and
53,711,608 weighted inclusive allocation bytes. Independent source review traced
the actual redundant preparation to Go's connection-bound StmtContext behavior;
see tic-gwindor. This is affirmative admission evidence for that consumer, not
an exact redundant-request count or predicted savings. Java TPS already retains
its handles; no duplicate Java cache is admitted.

The remaining gaps are aligned-window transaction/work denominators and
attributed wait/force/transport evidence, with explicit candidate dispositions
and matched validation still required. tic-nimloth now has a locally committed,
reviewed retry-safety boundary at 1e463e6d, but its full request/startup deadline
is incomplete. Ubuntu validation is deferred to the final sweep by the user.
No database workload ran during this offline reconciliation; tic-da4e remains
in progress and neither the original kernel trigger nor the three epics is closed.

### Narrow execution dispositions, 2026-09-14

The bitmap checkpoint 27033027, feature branch and annotated tag are now pushed.
Independent `execution_admission_review` approves telemnar's one-predicate
admission and the eowyn no-change rejection using the retained profile and
current source. Telemnar now links this investigation without waiting for its
unrelated wait/transport gaps. Eowyn's one sampled binding-view construction does
not justify the proposed lifetime/invalidation mechanism; no code is delivered.
Bracegirdle remains deferred until telemnar's remaining discovery cost is measured.
The investigation remains open for its declared unresolved evidence. No new
workstream, measurement framework or blanket implementation permission is added.
