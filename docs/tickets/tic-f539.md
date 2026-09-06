---
id: tic-f539
status: in_progress
type: investigation
priority: 2
assignee: blater
delivery: evidence
base-commit: 4ee882b752d89d21eb219646d5730213f4bd18f0
branch: ticket/tic-f539-commit-force-opportunity
tags:
    - performance
    - wal
    - transactions
created: 2026-09-06T17:27:49.450704Z
---
# Measure commit queue overlap with WAL force under current host load

Quantify whether queued commits can release locks materially earlier if preparation, append and publication overlap an earlier WAL force. Capture unchanged current-host TPS baselines before probes.

## Design

Evidence-only scope. Temporary timestamp/JFR probes in existing commit owners; no scheduling, durability, workload, client or batching changes. Separate actual enqueue-to-selection delay from preflight-inclusive QUEUE_RESIDENCE telemetry. Reconcile probe events to measured capture, compare loaded-host control/probe samples, preserve probe patch externally and remove production probes before delivery. Model overlap as an opportunity bound, never achieved TPS.

## Acceptance Criteria

Two untouched short TPS baselines before edits; longer controls and repeated measured probe samples with valid outcomes; quantified force-overlap opportunity and preparation-to-publication budget; slopmark before/after; independent concurrency/recovery review of interpretation and next-step safety constraints.


## Decision

The repeated traces confirm ready commits wait behind the current WAL force,
and often have enough observed preparation/publication time to fit in that
window. This is an optimistic local scheduling opportunity, not a demonstrated
TPS gain or proof that pipelining is the highest-impact change. The next priority
is the unchanged-master correctness failure tracked by [`tic-f8dd`](tic-f8dd.md).
No performance implementation or acceptance is authorized by this evidence while
that failure remains unexplained. All temporary production probes were removed.

## Baselines, runtime correction, and host conditions

Evidence root: `/private/tmp/river-commit-force-opportunity-20260906`.
The user's existing constant PC load was left running. River builds and workloads
were serialized. These are diagnostic samples, not exclusive-host evidence;
empty host-observation files do not prove exclusion of the user-declared load.

Stable master was `4ee882b752d89d21eb219646d5730213f4bd18f0`, whose production
source is the pushed read-durability checkpoint. Initial untouched 10-second
baselines were 154.2/159.9 TPS. Metadata later showed these and the main-checkout
long controls used OpenJDK 26.0.2.1, while worktree probes used GraalVM 25.0.4.
The initial control/probe TPS pairing is rejected. Configuration fingerprints
alone did not detect the launcher mismatch; original samples remain retained.

All probes were restored out at `147c3ac` before repeating untouched baselines
with an explicitly pinned launcher. The corrected short pair is **146.6/146.4
TPS**. Only after both finished was the reviewed patch reapplied at
`aba7764711af1b3d0162d2da6b9156be38b7a4e4`. The earlier probe revision was
`127e7bf`; the exact patch is retained as `probe.patch` in the evidence root.

Every corrected run uses:

```sh
RIVER_JAVA=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java \
  tools/tps-test.sh --seed=42 --warmup-seconds=1 --measured-seconds=10 \
  --output-dir=<evidence>/pinned-baseline-N
```

The launcher SHA-256 is
`6ce1e2affb90096e98f5257e6a7f03e4b6bf5b1e6087a3f7fe18f5655027273f`.
The fixed workload is tiny/standard, one warehouse, ten terminals, serializable,
no-wait stress, seed 42, 32 maximum attempts and normal synchronous WAL durability.
Long runs change warmup/duration to 5/30 seconds and add this option to both
control and probe runs, changing only the recording output filename:

```text
--server-java-option=-XX:StartFlightRecording=settings=<evidence>/opportunity.jfc,filename=<evidence>/<sample>.jfr,dumponexit=true,maxsize=64m
```

This bounded recording enables only the two custom timing events, without
stacks. It does not use the broader `--server-jfr` profiling configuration.
The client, workload, SQL, protocol, lock policy, retry policy, WAL force policy
and commit scheduling are unchanged.

| Pinned sample, execution order | Warmup / measured | TPS | Outcome |
| --- | --- | ---: | --- |
| pinned-baseline-1 | 1s / 10s | 146.600 | passed |
| pinned-baseline-2 | 1s / 10s | 146.400 | passed |
| pinned-control-long-1 | 5s / 30s | 176.100 | passed |
| pinned-probe-long-1 | 5s / 30s | 178.700 | passed |
| pinned-control-long-2 | 5s / 30s | 178.967 | passed |
| pinned-probe-long-2 | 5s / 30s | 176.933 | passed |

All six have zero measured retries/errors, passing invariants, reconciliation,
performance capture, terminal cleanup and successful receipts. Runtime launcher
path/hash, clean source and stable provenance were verified individually. The
long control/probe configurations match; fingerprints are
`4f327e37f30c3177a1ea21f5daeda574e865d4dce5ba9d630d136b84d9f88553`.
Controls and probes show no repeated directional TPS shift. This checks the
instrumentation's performance context, not a production speed improvement.

## Timing result

| Trace | Writes | Cohorts | Writes overlapping force | Mean overlap, affected writes | Force share of actual queue wait | Cohorts whose work fits |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| pinned-probe-long-1 | 4797 | 4737 | 1973 (41.1%) | 1.554 ms | 96.90% | 1363/4737 (28.8%) |
| pinned-probe-long-2 | 4748 | 4711 | 1968 (41.4%) | 1.506 ms | 96.73% | 1359/4711 (28.8%) |

The counterfactual retains observed cohort membership, starts no earlier than
the last member's enqueue and the preceding force's start, and uses observed
process-through-publication cost. Fits are 1,363/1,916 available windows in the
first trace and 1,359/1,936 in the second (71.1% and 70.2%). They represent about
29% of all cohorts, not 71% of all commits. Deduplicated queue/force overlap is
2.990/2.929 seconds over approximately 30-second captures. These wall-time
fractions are neither achieved TPS gains nor general throughput ceilings.

`QUEUE_RESIDENCE` is attributed after group preflight. In the first corrected
trace it totals 5.076 aggregate seconds; actual enqueue-to-selection time is
measured separately. Its preflight-inclusive value must not be presented as
pure queue wait. `analyze.py` intersects actual queue intervals with the union
of preceding force intervals, separating summed request delays from wall time.

Events reconcile exactly with capture writes, cohort counts and total force
nanoseconds. Capture includes the quiescent drain boundary; published means
return from group publication after lock release, while completed is before
session cleanup and notification. No lock-key/dependency identities were recorded,
so these queued requests cannot be labelled causal successors. Observed physical
work may cost differently under overlap; this experiment neither runs a dynamic
pipeline nor proves any later append can share an already-running force.

## Correctness blocker and retained rejected evidence

Initial sequence: control-long-1 172.700 TPS; probe-long-1 172.833;
control-long-2 167.567; probe-long-2 175.967; control-long-3 invalid.
As above, the initial TPS pairing mixes JVMs and is rejected.

On clean unchanged master under OpenJDK 26.0.2.1, `control-long-3` reached
post-run invariant verification and `SELECT COUNT(*) FROM order_line` returned
`CORRUPTION`. This occurred before the final CHECKPOINT command; it is not a
proven cardinality mismatch or an identified checkpointing defect. Shutdown
also returned database `CORRUPTION`. The receipt is `evidence_invalid`, no
accepted artifact was published, and there is no valid TPS figure. Measured
retry/error counters were zero. No cause is attributed to JVM choice, host load
or an earlier change. Subsequent passing controls do not clear this failure.
See [`tic-f8dd`](tic-f8dd.md) for the separate root-cause and fix gate.

## Review, restoration, and next design boundary

Independent concurrency/recovery review accepted the timestamp handoff and
writer-confined reusable event ownership, independently recomputed first-trace
interval/model arithmetic, and verified the corruption classification. Full
review notes and analysis outputs are retained in the evidence root.

The future pipeline cannot be implemented by merely dispatching force to another
thread. `LocalWal.markForced` currently samples mutable `tailEnd` and the latest
appended sequence after force returns: concurrent append needs a frozen,
prefix-specific force boundary. `IndexedHybridCommitGroup`, its prepared pages,
operation scratch, phase and pending-durability marker have one active owner.
Multiple cohorts need bounded independent retention and ordered publication,
completion and failure fencing. Publication pins must remain until their WAL
prefix is durable, preserving WAL-before-page-write. Relate any future design
to [`tic-b368`](tic-b368.md) without bypassing that ticket's dependencies.

Slopmark before/probe/restored: coordinator **71.3503**, batch **30.3582**,
request and metrics **0**; unchanged throughout. Both temporary event classes
score 0 and were removed. No batching delay, second commit path or benchmark
semantics were introduced into production. Targeted runtime builds passed;
restored source is byte-for-byte identical to master across production, tests
and tooling. No new production mechanism is delivered, so a clean full test
checkpoint and a new performance tag are not claimed. The latest accepted
performance checkpoint remains the read-durability checkpoint.
