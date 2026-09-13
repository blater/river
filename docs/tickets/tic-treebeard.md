---
id: tic-treebeard
status: closed
type: bug
assignee: blater
parent: tic-rowlie
delivery: code
base-commit: ef935596225eb78cf400379bfbb5d0d78de4a530
branch: ticket/tic-treebeard-shutdown-deadline
delivered-commit: 55dc899e8ba2a6ae2f6b8dc92cd83410573fea3b
checkpoint-tag: perf-checkpoint-20260913-shutdown-deadline
tags:
    - safety
    - process
    - performance
created: 2026-09-13T16:12:37.029801Z
---
# Bound TPS process shutdown and preserve unreaped-server evidence

Enforce one 15-second runner/server shutdown budget, never wait indefinitely for an unreaped process, preserve its database and evidence, and report failure without retry; this does not guarantee kernel resource release.

### Scope and validation boundary

This ticket changes runner and Java server shutdown supervision. Use one overall deadline
of at most 15 seconds for the owned runner and server; do not restart the budget
for each signal/process or repeat cleanup on failure. Wait/reap only after the
process is known terminal. If it remains live, report PID and retained paths and
exit failure before copying logs, writing metadata or removing database files.
Do not claim that SIGKILL, PID disappearance or a timeout releases a kernel lock.

Required shell cases: normal exit; graceful/TERM/KILL stages within one
budget; unreaped process; two processes sharing the budget; repeated cleanup;
nonzero status and preservation on failure. The user subsequently reauthorized
Java builds and TPS with the 15-second stop deadline. Run one guarded diagnostic
at a time; never retry automatically after a deadline failure.

Pending-request responsiveness during workload execution is separately owned by
tic-nimloth; the shutdown fix alone cannot establish kernel safety.
Independent source review must precede acceptance.

### Implementation and review status, 2026-09-13

The working patch shares one shutdown budget, caps the stop option at 15 seconds,
uses a Bash job snapshot instead of an external process-table command, and avoids
waiting on a child that is still active. A latched cleanup failure retains data
and evidence and exits before publication, metadata writes or removal. Runtime
version metadata is captured before cleanup rather than launching Java from it.
Because Bash measures elapsed seconds at whole-second resolution, the polling
budget reserves one second. This bounds intentional polling while the shell is
schedulable; it cannot impose a hard deadline on blocked kernel calls.

Lead validation passed separate `bash -n` checks for `tools/tps-test.sh` and
`tools/tests/tps-shutdown-test.sh`, the virtual-clock/process test, and
`git diff --check`. Completed children are checked before signals, including
after deadline expiry; the test asserts no TERM, KILL or stop-file request for
those children. Tests also cover shared budget, stopped/unknown states, failed
job snapshots, one or both children remaining live, repeated cleanup and the
cleanup function's early exit before persistence or removal.
The earlier shell review stopped with a platform safety-system block, reason
"Potentially unintended activity". Following explicit runtime reauthorization,
real TPS exposed and corrected false success after forced server termination.
Nonzero server exit also invalidates the result. Java's unbounded worker join
was replaced with a shared five-second join deadline; repeated close retains
failure, and the instance retains every worker dependency after nonterminal
server shutdown. Independent Astra review approved the expanded Java and shell
failure-handling scope after correction of lastStatus reporting. This approval
does not claim a hard deadline for native calls or monitor acquisition.

Full LoopbackRiverServerTest and RiverDaemonInstanceJdbcIntegrationTest classes
pass under the external 15-second command guard. The instance test verifies
all dependency references and the identity lock survive worker timeout. A newly
installed TPS distribution then passed the four-worker checkpoint/shutdown smoke
with no forced termination or errors. See exact commands, artifacts, limitations
and kernel evidence in [the investigation](../plans/checkpoint-kernel-hang-20260913.md).
Delivery validation also passed the full river-server test module, all 15
river-server-app test classes in bounded batches, verifySourcePolicy and
verifyModuleGraph. The initial combined app-suite command reached the absolute
15-second command cutoff; no Java process remained. Named batches then completed
the coverage within the same per-command bound (unit/policy batch 5 seconds,
CLI batch 2 seconds, process-test class 8 seconds; instance class 10 seconds).
This is a lifecycle correctness delivery, not a throughput improvement claim.

