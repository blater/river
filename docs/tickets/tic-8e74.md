---
id: tic-8e74
status: closed
type: story
assignee: blater
parent: tic-5db4
delivery: code
base-commit: fa77adceeae9e1a0702971d122617b925a53701f
branch: ticket/tic-8e74-terminal-snapshot-gauge
delivered-commit: 2d8e4cb307135102a5457c5ffae9305f0183b0f6
checkpoint-tag: perf-checkpoint-20260907-retained-snapshot-gauge
tags:
    - performance
    - tpcc
    - p0
    - transactions
    - observability
deps:
    - tic-5cc0
created: 2026-09-04T23:19:29Z
---
# Expose terminal retained-snapshot cleanup for P0

Expose the missing transaction-lifecycle cleanup fact required by the P0
failure-mode displacement gate without coupling it to lock diagnostics.

## Outcome

The cold terminal diagnostics report the exact active retained-snapshot count,
allowing `tic-1dda` to prove it is zero after every run.

## In Scope / Owning Mechanism

The transaction manager's existing snapshot registry count remains the single
source of truth. The existing cold server diagnostics boundary exposes that
count without retaining another counter.

## Non-goals

- Classify locks, add per-snapshot events, or duplicate snapshot lifecycle
  state in diagnostics.
- Change snapshot admission, visibility, reclamation, transaction cleanup, or
  benchmark execution.
- Introduce a metrics registry, sampler, background poller, or TPC-C-specific
  transaction-layer type.

## Stop Conditions

Stop if the terminal value cannot be read from the canonical snapshot registry
or if exposing it would require a second lifecycle counter. Any observed
non-zero terminal count is a correctness finding for a new fix ticket; it must
not expand this observability delivery.

## Maximum Change Shape

Add one cold read of the existing registry count through the current
transaction-manager and diagnostics path, plus focused lifecycle and output
tests. Do not add hot-path writes, retained histories, alternate cleanup paths,
or transaction behavior changes.

## Design

Expose the existing active-snapshot registry count through the cold diagnostics
surface consumed by `tools/tps-test.sh`. Preserve the transaction layer as the
canonical owner and keep collection outside transaction execution.

## Acceptance Criteria

Focused tests prove the reported value follows begin, commit, abort, failed
admission, and terminal cleanup using the existing lifecycle count; the value
is zero after complete cleanup and detects a deliberately retained active
snapshot. Disabled workload diagnostics gain no hot-path allocation, clock
read, counter update, or control-flow change. `tic-1dda` can retain the terminal
value without parsing lock-classification state.

## Delivery evidence

Candidate `79f4577` exposes only the canonical cold registry gauge. Independent
review approved the implementation and lifecycle coverage. Clean full tests:
1,805 tests, zero failures/errors, two existing skips. Source/bytecode policy
retains 259 pre-existing violations with no delta.

Matched short TPS: before 161.400/157.300, after 161.900/160.500. All runs passed
invariants with zero errors/retries; both candidate terminal counts are zero.
No repeated regression identified; no speedup or equivalence claim. Current
provenance/host gaps remain separate P0 prerequisites. Full details and tail
latency caveats are in `docs/performance-checkpoints.md`; raw evidence is at
`/private/tmp/river-tic-8e74-evidence-20260907`.

The exact merge smoke had one fully reconciled Delivery deadlock retry, zero
errors and complete cleanup. This triggered longer interleaved A/B/A/B runs
before closure: 162.067 / 162.767 / 176.967 / 165.033 TPS, all zero retries and
errors. The direction differs between pairs; no repeated regression identified.
The smoke cycle identity remains unknown because detailed capture was disabled.
The new getter executes only at final metrics capture after workload completion.
The checkpoint ledger retains the full sequence and qualification.
