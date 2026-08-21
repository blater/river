# P09 local prototype review

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Integrated commits: `ae90cfa`, `5a3b0de`, `a848196`

Evidence class: developer-only mechanism evidence; not P05, P09, or G0
promotion evidence

## Scope

The isolated `river-bench` prototype lane now contains disposable mechanisms
for all five P09 evidence families:

- a preallocated direct WAL reservation, encode, checksum, publication, and
  consume ring;
- primitive column vectors with a reusable selection vector;
- positional 8/16/32 KiB temporary-file reads, writes, and force calls;
- repeated-epoch FPI-versus-double-write byte, copy, and force accounting; and
- a fixed-layout direct version store with append, read, and visibility scans.

JMH/JOL wiring and a machine-readable local smoke make the mechanisms
inspectable. Production modules do not depend on `river-bench`, and none of
these layouts or algorithms is an accepted product format or API.

## Independent review

The first review rejected integration because a reservation could be encoded
again after publication, changing already-visible bytes. It also required the
documentation to stop implying complete checkpoint-storm, version-store, or
WAL evidence.

The corrected branch added a terminal reservation lifecycle, rejected active
carrier reuse before advancing the claim counter, preserved immutable
publication, and added a deterministic delayed-hole/saturation/drain scenario.
It also added append/read measurement, repeated page-protection epochs, explicit
copy classes, and a manifest of canonical limitations.

The final independent review found no blocker or required issue for integration
as developer-only scaffolding. It explicitly retained small-orchestrated-MPSC,
contention, and Java-memory-model stress as future evidence.

## Local validation

At integrated commit `a848196`:

- `RIVER_GRADLE_HOME=/private/tmp/river-gradle-home ./verify --rerun-tasks`
  completed successfully with 75 actionable tasks;
- 104 tests ran with zero failures, errors, or skips; and
- `:river-bench:prototypeSmoke` completed successfully and wrote a clean-commit
  manifest plus results under `river-bench/build/prototype-smoke`.

The small MPSC smoke consumed all 10,000 records, observed the deliberate
publication hole, reported saturation, and recovered bounded capacity. These
values are a functional smoke, not a throughput claim.

## Required before P09 can pass

- Run on declared P05 hardware with repeated samples, confidence intervals,
  calibrated storage/filesystem behavior, and numeric budgets.
- Add WAL framing, block/segment rollover, real force/group-commit, crash,
  corruption, abandonment, and high-contention evidence.
- Compare version persistence layouts, point lookups, append/write
  amplification, recovery, maintenance, and long-running boundedness.
- Exercise page protection with measured I/O, recovery, checkpoint storms, and
  write amplification rather than arithmetic accounting alone.
- Run vector scans on the accepted workloads with allocation, copy, cache, and
  tail-latency profiles.

## Promotion decision

P09 is `implemented`, not `passed`: all five disposable mechanism families
exist, but the canonical P05 runs, numeric budgets, expanded evidence above,
and final performance review remain open. P05 and G0 remain unpromoted, and no
milestone tag is authorized by this evidence.
