# P05 local benchmark-harness evidence

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Integrated commits: `88b4dcd`, `b9c1836`, `777f227`

Evidence class: reviewed developer harness infrastructure; not a P05 baseline,
numeric budget, or G0 promotion result

## Scope

`river-bench` now provides:

- versioned JSON Schemas for manifests, results, and sample rows;
- strict duplicate-key, trailing-document, reference, checksum, and latency
  semantic validation;
- deterministic bounded `riverbank_tiny` and `riverpapers_tiny` generators with
  pinned tiny fixtures and checksums;
- HdrHistogram closed-loop service and open-loop intended-schedule latency,
  plus an explicitly secondary expected-interval-corrected service view; and
- create-once JSON/TSV artifact publication that binds every referenced file by
  SHA-256.

Publication snapshots and preflights every payload, stages on the target
filesystem, verifies persisted bytes and result references, exclusively claims
the run-id directory without clobber, and atomically installs the complete tree
as `run-id/artifacts/`. The claim directory alone is never a completion signal.
A reader must validate `result.json` and every referenced digest.

No external dataset was downloaded. Kaggle and other realism adapters remain
explicitly provenance-gated backlog items.

## Independent review

The first review rejected caller-trusted workload checksums, permissive JSON
and sample semantics, and non-transactional publication into the final
directory. The correction added strict validation, byte snapshots, verified
staging, atomic tree publication, and conservative owned recovery.

The second review found that an empty unmarked staging directory could be
deleted and that `ATOMIC_MOVE` did not itself prove no-replace behavior. The
final correction validates ownership before every recovery deletion and uses
exclusive directory creation to claim the final run ID before atomically
installing its `artifacts` child. Deterministic interposition proves a competing
target and sentinel created between preflight and claim are preserved.

The final review found no remaining blocker or required issue for integration
as developer-only P05 infrastructure.

## Local validation

On the integrated branch:

- `:river-bench:test --rerun-tasks :river-bench:benchmarkSmoke` completed
  successfully with all 39 invoked tasks executed; and
- the smoke wrote a verified artifact tree under
  `river-bench/build/benchmark-smoke/<run-id>/artifacts`.

At integrated commit `c563547`, the repository's authoritative
`RIVER_GRADLE_HOME=/private/tmp/river-gradle-home ./verify --rerun-tasks` gate
also completed successfully. Both clean, uncached archive builds matched the
exact expected set, the final check executed 86 tasks, and 151 tests ran with
zero failures, errors, or skips.

## Required before P05 can pass

- Complete streaming, scale-controlled canonical RiverBank/RiverPapers
  generators and workload semantics review.
- Record and calibrate the current physical development host, including CPU,
  memory, power/thermal state, filesystem, physical storage, JDK, and background
  activity controls.
- Run repeated same-host samples with immutable raw artifacts. A
  provenance-approved Ingres baseline is authorized but optional and may be
  deferred; River's numeric budgets do not depend on a direct comparison.
- Add warmup/steady-state rules, confidence intervals, control variance,
  saturation sweeps, allocation/copy counters, recovery measurements, and
  independent performance review.
- Freeze numeric latency, throughput, allocation, copy, queue, recovery, and
  resource-growth budgets only from that canonical evidence.

## Promotion decision

P05 remains `active`. The harness is implemented local infrastructure, but no
repeated declared-host result set or accepted numeric budget exists. P09, P06,
G0, and M0 remain unpromoted, and no milestone tag is authorized.
