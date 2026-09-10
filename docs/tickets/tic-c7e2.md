---
id: tic-c7e2
status: closed
type: story
priority: 1
delivery: code
created: 2026-09-10
base-commit: 7444f542ef54ff3f07eb7bba0c78c18645fe6f7f
branch: ticket/tic-c7e2-wal-range-sync
deps:
  - tic-9f2c
---
# Synchronize only the pending contiguous WAL interval

Follow-up to the one-sync commit groups in
[`tic-9f2c`](tic-9f2c.md). Reduce the mapped range submitted for synchronization
while preserving synchronous acknowledgement and whole-group recovery.

## Problem and evidence

`NioMappedWindow.force()` currently calls `segment.force()` over the complete
mapping. The data window is 16 MiB, even when the pending commit occupies only
a small part of it. A broad synchronization request does not imply all those
bytes are written to storage; its contribution to elapsed time needs measuring.

The latest one-worker INSERT probe measured one actual `msync` per commit,
averaging 105.2 us. The earlier MariaDB redo `fsync` measurement was 21.5 us.
River's previous 57.2 us/call averaged its data and end-marker syncs together;
it is not a measurement of the data sync alone. The current evidence establishes
a latency gap, not that mapping length is its cause.

Source checkpoint: `perf-checkpoint-20260910-atomic-wal-sync` (`7444f542`).
Artifacts: `/private/tmp/wal-atomic-validation/insert-sync` and
`/private/tmp/insert-elapsed-20260910`. Take fresh adjacent baselines before
implementation rather than treating these historical timings as controls.

## Contract and implementation scope

Redo is append-only within a WAL generation. Express pending durability as one
half-open interval of physical WAL byte offsets:

```text
[durableEnd] -- appended records and complete group footer -- [captured force end]
```

- WAL owns the durable frontier and captured force target. Seal the target
  before I/O, include the complete group footer, and advance the frontier only
  after successful synchronization. Provider eviction must not publish a WAL
  commit or satisfy quorum acknowledgement on its own.
- Pass the pending interval through the durable-file boundary. The mapped
  provider translates it into the relevant resident mapping portions and any
  required OS page alignment. Do not remap already synchronized, evicted portions
  merely to force them again. Preserve metadata synchronization required by file
  growth, and preserve bootstrap, truncation and recovery-repair ordering.
- Mapping eviction must synchronize pending bytes before releasing their
  mapping. Any eviction, range-force or metadata-force failure propagates and
  prevents acknowledgement; do not clear unsynchronized state on failure.
- Use the existing serialized WAL owner and mapping lifetime. Any provider
  bookkeeping needed for eviction describes mapped append coverage only; do not
  add a second logical durable frontier or a general dirty-range collection.
- Use long file offsets, bounded mapping-local offsets, and overflow-safe
  interval arithmetic. Normal commits inside one mapping retain one underlying
  sync. Boundary crossings may require more than one mapped operation.
- Keep the normal WAL force path singular. Update River-owned providers and
  fault fakes together. Full-file force remains appropriate for existing
  non-WAL and maintenance consumers; it is not an alternate ordinary WAL path.
- Reuse storage and result carriers. Check that narrowing the mapped view does
  not introduce steady-state allocations, payload copies or remapping per commit.

No new WAL format, dirty-page framework, writer/flusher threads, executor,
durability mode, deferred acknowledgement, batching policy, or benchmark change.
Keep the current checksums and group recovery rules. This change does not assume
arbitrary-size atomic writes or alter the platform power-loss contract.

## Acceptance and validation

1. Focused provider tests prove exact requested coverage for several appends in
   one group, successive groups, and groups crossing the 4 KiB header boundary
   or 16 MiB mapping boundary. Cover a footer crossing a boundary, eviction,
   file growth, and append after recovery truncation. Reuse existing WAL crash
   tests instead of duplicating the whole recovery matrix.
2. Inject synchronization failures and prove the durable frontier and
   acknowledgement do not advance. Check that previously acknowledged groups
   survive restart and incomplete groups retain the accepted recovery behavior.
3. Extend the external diagnostic syscall timer to record requested `msync`
   length, calls and elapsed time by length. Compare the same one-worker INSERT
   workload before and after, reporting both per-call and per-transaction costs.
   Distinguish requested range from bytes actually written to the device.
4. Capture at least two matching TPS samples before and after with unchanged
   workload, isolation, durability and runtime configuration. Investigate a
   repeated regression or new errors; claim a latency improvement only if the
   measurements support it. A new MariaDB run is optional context, not a gate.
5. Obtain a focused independent recovery/ownership review; compare slopmark on
   touched production files. Run affected tests, the clean integration check,
   and actual standalone commit/SIGKILL/restart/public-stop validation. Follow
   the existing native O3/PGO build and `--no-daemon` rules without adding a new
   platform or benchmark matrix.

Record commands, individual samples, actual sync lengths, correctness and cleanup
outcomes, and the decision in `docs/performance-checkpoints.md`. Deliver one
feature branch and checkpoint under the existing promotion process. If narrowing
the range does not reduce sync cost, record that result rather than expanding
this ticket into an unrelated I/O redesign.

## Delivery evidence

Implemented the explicit WAL force interval through all River-owned providers
and fault fakes. The mapped provider reuses a mapped buffer, retains bounded
pending coverage for eviction, and preserves metadata/failure ordering. No WAL
format change or deferred implementation component.

Clean full checks and O3/PGO native build passed. Actual native commit,
SIGKILL/restart, all-row/value verification and public stop passed. Independent
recovery review and slopmark review accepted the change.

Interleaved INSERT controls averaged 113.3–124.9 us per sync with 16 MiB requests;
candidates averaged 23.8–24.6 us with about 9 KiB requests. All row checks passed.
Initial low short TPS samples triggered longer interleaved runs: controls
253.367/256.333, candidates 256.433/266.567 TPS. The original short configuration
was rechecked adjacently at 211.5/207.0 TPS. The earlier large drop did not
reproduce; no sustained TPS regression or qualified TPS gain is claimed. One
PAYMENT deadlock retry reconciled correctly; all runs had zero errors and clean
final transaction/lock state. Full commands, individual samples, limitations and
artifact paths are in [`performance-checkpoints.md`](../performance-checkpoints.md).

Checkpoint: `perf-checkpoint-20260910-wal-range-sync`.
