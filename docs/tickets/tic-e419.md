---
id: tic-e419
status: closed
type: task
priority: 1
assignee: blater
parent: tic-a51d
delivery: code
created: 2026-09-09T11:44:09.878331Z
---
# Replace byte assembly with fixed-endian word access

Use static fixed-endian ByteBuffer-view VarHandles in FormatBytes and remove BTreePage/BTreeKeyLayout duplicate byte primitives. Preserve format bytes and buffer state; no new allocations, synchronization or fallback paths. Exclude CRC, key comparison and broader buffer changes.

## Acceptance Criteria

Focused primitive/format/storage correctness tests, affected-module tests and native lifecycle pass. Compare unchanged JVM and native O3 configurations against the current 154.6 and 129.6 TPS baselines; record individual samples, invariants, cleanup and slopmark. No merge with an unexplained regression.


Branch: `ticket/tic-e419-fixed-width-access`, based on native candidate `349de73e`.
The source change is isolated from the native packaging work. Keep native `-O3`
and JVM settings fixed for the requested 129.6 / 154.6 TPS comparisons.

## Delivery

Implemented static plain fixed-endian word access and removed both B-tree copies.
Independent lead review checked format compatibility, primitive call signatures,
allocation shape, buffer bounds/state and native machine code. Clean full tests
and the copied native executable lifecycle passed. Global check still fails on
unchanged source-policy findings documented in the performance checkpoint.

JVM samples: 174.2 / 174.0 TPS versus 154.6 requested baseline (153.4 adjacent
control). Native O3: 143.6 / 141.7 versus 129.6 (130.0 adjacent control).
Zero retries/failures and successful invariant/cleanup checks in every run.
Slopmark did not worsen. See the tic-e419 entry in
[performance checkpoints](../performance-checkpoints.md) for commands and artifacts.
This closes implementation and the requested short diagnostic comparison; it does
not promote the feature or close native packaging acceptance under tic-a51d.
