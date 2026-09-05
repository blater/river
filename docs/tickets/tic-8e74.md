---
id: tic-8e74
status: open
type: story
assignee: blater
parent: tic-5db4
delivery: code
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
