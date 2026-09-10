---
id: tic-6f28
status: in_progress
type: story
priority: 1
delivery: code
created: 2026-09-10
owner: root
branch: ticket/tic-6f28-one-way-prepared-close
base-commit: 822fdf057e257bc3493094678b03ed81a026d412
worktree: /private/tmp/river-one-way-close
---
# Release prepared handles without an acknowledgement round trip

The four-worker full-mix trace recorded 927,918 small socket writes in 30 seconds.
The actual shared Go workload prepares a transaction-owned statement per SQL helper;
River then awaits every close acknowledgement, while MariaDB closes one-way.
A successful ten-line New Order has 140 River awaited exchanges versus 95 for
MariaDB. Forty-six River replies only acknowledge statement disposal. Evidence:
`/private/tmp/river-write-rootcause-20260910/root-cause.md`.

## Contract and scope

Protocol v5 makes CLOSE_PREPARED one-way. A valid release produces no response;
invalid/malformed/unauthenticated release terminates the connection without an
unsolicited response. Transport ordering makes a later ordinary response a barrier
for earlier releases. Client close success means the release was written, not a
remote acknowledgement. Preserve synchronous local input/state checks and transport
failure reporting. Replace v4 behavior and migrate Java/JDBC and the Go adapter
in river-harness together; no dual-version path.

A program retains its shared plan directly. Closing its original public statement
handle invalidates that handle immediately; program execution remains valid and
its close releases the last plan reference. Remove the old handle-pinning state.
Keep retained storage budgeted and session cleanup authoritative.

No SQL, transaction boundary, workload/mix, TLS or durability changes. Row batching
and wider prepare/execute protocol changes remain separate.

## Acceptance and delivery

Test no-reply close followed by a normal command/query, repeated/idempotent client
close, write failure, invalid release termination, program execution after handle
close, and final budget/session cleanup. Independent review checks transport framing
and retained lifetimes. Use focused affected tests, slopmark and a clean River check;
run the Go suite/race/vet/build as required by its repository. Capture two matched
four-worker sample/all JVM controls and candidates, and socket-write evidence for
the mechanism. One standalone smoke verifies the coordinated protocol migration.
Record results, merge/tag/push both repositories, refresh local runnable artifacts.
Go adapter branch: ticket/tic-6f28-one-way-prepared-close, base 3c5643b,
worktree /private/tmp/river-harness-one-way-close.


## Delivery evidence

Implementation: River `05c0f7e4`; Go adapter `793e80d`, locally integrated on
harness `main` at `aba7c43`. Full evidence and commands are in
[performance checkpoints](../performance-checkpoints.md#2026-09-10--one-way-prepared-release-tic-6f28)
and `/private/tmp/river-tic-6f28`.

- Focused ownership/transport tests and clean River check passed (4m13s).
- Full Go tests, race tests, vet and build passed. Independent Java boundary
  review and integrator engine/Go review found no blocking issue.
- Short JVM controls: 325.31 / 366.91 TPS; candidates: 428.82 / 414.37.
  Longer adjacent pair: 392.58 -> 429.27 TPS (+9.3%). All correctness and cleanup
  checks passed. New Order retries increased; the checkpoint records the cost
  and the limits of the contention inference.
- JFR socket writes: 92.51 -> 65.65 per committed transaction, including retries
  (29.0% fewer). Median syscall duration stayed near 3.6 microseconds.
- O3/PGO build and actual standalone authenticated full-mix smoke passed.

River is accepted for integration under
`perf-checkpoint-20260910-one-way-prepared-close`. The matching harness is merged
and tagged locally. The only remaining delivery item is publishing the harness:
its Git repository has no remote configured, and the user has been asked for the
URL. Keep this ticket open until that publication is complete.
