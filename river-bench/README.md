# River P09 prototypes

Status: partial P09 developer evidence; not P09 completion, a production API,
format, or gate

This module holds mechanisms used to test Phase 0 design assumptions. Production
modules must never depend on `river-bench`, and none of the layouts, checksums,
status mappings, queue algorithms, or measurement results here are authoritative.
WAL event/byte counters use race-safe atomics. Maximum occupancy is an observed
high-water diagnostic rather than a queue correctness invariant.

It also contains the local P05 harness infrastructure: versioned manifest,
result, and sample schemas; partial in-memory `riverbank_tiny` and
`riverpapers_tiny` schema-v1 generators; bounded HdrHistogram latency
accounting; and atomically published local JSON/TSV artifacts. These generators
prove small-fixture determinism and workload shape only. They are not the full
canonical RiverBank/RiverPapers schemas or streaming scale generators. This is
functional evidence for the harness, not a P05 baseline.

The current mechanisms cover:

- bounded preallocated WAL claim/direct-encode/checksum/gap-free publication,
  including a small two-producer delayed-hole and saturation-recovery scenario;
- reusable primitive columns and selection-vector scans;
- 8/16/32 KiB positional NIO read/write/force against owned temporary files;
- first-page-image versus double-write first-dirty/redirty/checkpoint-storm
  accounting, retaining WAL/staging/data bytes and force classes separately; and
- a fixed-layout append/read/visibility-scan version-store alternative.

Run deterministic tests and all repository checks with:

```shell
RIVER_GRADLE_HOME=/private/tmp/river-gradle-home ./verify
```

Run the short local measurement with:

```shell
RIVER_GRADLE_HOME=/private/tmp/river-gradle-home \
  ./gradlew :river-bench:prototypeSmoke
```

It writes `results.json` and `manifest.json` under
`river-bench/build/prototype-smoke`. The files identify themselves as developer
evidence only, record environment and JOL layout details, and explicitly do not
freeze budgets or claim P05/G0. The NIO numbers include `FileChannel.force(false)`
on the developer machine and are not storage-device durability evidence.
The smoke intentionally samples only 10,000 hot-path iterations; use JMH and a
dedicated manifest for decisions or stable comparisons.

The page-protection comparison is a deterministic accounting model. It treats
the first-dirty FPI as an immutable page-image copy and the double-write path as
a checkpoint staging copy, resets dirty state at every modeled checkpoint
epoch, and reports those copies separately. It does not implement either
recovery mechanism.

For a short JMH mechanism check, run `:river-bench:jmhSmoke`. Normal JMH defaults
on `MechanismBenchmark` provide longer warmup and measurement runs when invoked
directly. Neither command is a release gate.

## P09 evidence still required

- dedicated-runner repetition, control variance, allocation profiling, and
  multi-core producer/concurrency sweeps;
- real WAL segmentation, rollover, group-force ordering, durable media, crash,
  and recovery measurements;
- production-representative page dirties, checkpoint scheduling, write
  coalescing, FPI compression choices, and double-write recovery behavior;
- comparison against accepted ADR assumptions followed by numeric budget
  review.

Until those exist, this module informs design discussion only and must not be
used to mark P09, P05, or G0 complete.

Run `:river-bench:benchmarkSmoke` for the local harness check. It writes a new
create-once directory under `build/benchmark-smoke` by verifying a same-filesystem
staging directory, exclusively claiming the run-id directory with a no-clobber
directory creation, and atomically installing the verified tree as its
`artifacts/` child. The run-id parent is only a claim and may remain incomplete
after a process or machine crash. A reader accepts a run as complete only when
`run-id/artifacts/` exists, `result.json` passes schema validation, and every
digest referenced by that result verifies. `result.json` is written last inside
staging and verified before the whole tree becomes visible. The no-clobber
guarantee covers writers that compete for the run-id path; it is not a security
boundary against another process mutating the contents of an already claimed
directory. The harness labels its synthetic measurements as developer smoke.
Open-loop rows report service latency,
intended-schedule latency (the primary coordinated-omission-safe view), and an
HdrHistogram expected-interval-corrected service diagnostic. Closed-loop rows
report service latency only.
