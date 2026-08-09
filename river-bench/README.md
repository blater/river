# River P09 prototypes

Status: partial P09 developer evidence; not P09 completion, a production API,
format, or gate

This module holds mechanisms used to test Phase 0 design assumptions. Production
modules must never depend on `river-bench`, and none of the layouts, checksums,
status mappings, queue algorithms, or measurement results here are authoritative.
WAL event/byte counters use race-safe atomics. Maximum occupancy is an observed
high-water diagnostic rather than a queue correctness invariant.

It also contains the local P05 harness infrastructure: versioned manifest,
result, and sample schemas; the original in-memory `riverbank_tiny` and
`riverpapers_tiny` schema-v1 fixtures; bounded HdrHistogram latency accounting;
and atomically published local JSON/TSV artifacts. Schema v2 adds bounded-memory,
scale-controlled generated RiverBank and RiverPapers relational tables. This is
functional evidence for the harness, not a P05 baseline.

## Generated relational workloads

`RiverBankScale` controls branch, account, transaction, and hot-account counts.
The exact v2 columns and invariants are recorded in [WORKLOADS.md](WORKLOADS.md).
The generator emits:

- `riverbank_accounts`: unique positive account IDs, branch/customer keys,
  2020-2024 epoch-millisecond open times, active/frozen state, non-negative
  minor-unit balances, and bounded risk bands; and
- `riverbank_transactions`: unique transaction and idempotency IDs,
  2020-2024 epoch-millisecond event times, five declared operation types,
  nullable account references encoded as empty TSV fields, and positive bounded
  minor-unit amounts. Transfers have two different accounts. Deposits have only
  a destination. Other current operations have only a source. Eighty percent
  of account selections target the declared hot set; the cold lane selects
  across all accounts and can therefore also encounter a hot account.

`RiverPapersScale` controls document, author, institution, and minimum/maximum
abstract-token counts. The v2 generator emits `riverpapers_authors`,
`riverpapers_documents`, and `riverpapers_document_authors`. Documents have a
unique synthetic DOI, common-prefix title, institution, 2020-2024 epoch day,
version, skewed category, nullable publication DOI, and bounded River-owned UTF-8
text. Each document has exactly three distinct author relations. Repeated,
category-correlated, rare, and non-ASCII tokens exercise B+tree-compatible
metadata predicates, scans, tuple width, and future text-token pipelines; they
do not claim that River implements a full-text index.

Both generators are stateless by row: seed, row sequence, and value lane fully
determine each value. The output is invariant across caller scratch-buffer
sizes and table generation order. Generation uses a caller-supplied bounded
byte array, manual numeric encoding, and static token bytes rather than building
per-row objects. Publication performs a digest-only preflight and a second
streaming pass, then verifies row count, byte count, SHA-256, and the persisted
file before the existing create-once atomic tree install. Memory is bounded by
artifact metadata and the 64 KiB publication scratch buffer, not row count.

Scale labels record intent; this slice deliberately does not assign `cache`,
`memory-pressure`, `storage`, or `history` cardinalities before P05 hardware and
buffer-pool sizing are accepted. No external dataset is downloaded or mimicked.
Kaggle adapters and comparison claims remain provenance gated.

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

Run `:river-bench:workloadSmoke` for the developer-only schema-v2 streaming
check. It generates the small declared RiverBank/RiverPapers scale, publishes a
create-once v2 artifact tree, and makes no throughput, allocation-budget, P05,
or G0 claim.
