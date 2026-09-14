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
# Preserve shutdown ownership and unreaped-server evidence

### Policy withdrawal, 2026-09-14

The user withdrew the 15-second rule and its implementation. The TPS maximum-15
option restriction and default are removed; the prior configurable 20-second
stop default is restored. The added Java worker-join cutoff and its specific
fixtures are removed. Existing acceptor timing is restored.

Conditional reaping, failure reporting, shutdown-status retention and protection
of resources still owned by live threads remain correctness requirements. These
changes do not establish the cause of the CHECKPOINT kernel stall.

The following implementation/validation record describes the original delivery;
its timeout rules are withdrawn and must not be used as current instructions.

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


### Policy-withdrawal integration, 2026-09-14

The previously authorized local withdrawal is integrated before resuming P0.
Worker joining and acceptor timing match the pre-policy owner; the TPS stop
budget again defaults to 20 seconds without the withdrawn 15-second maximum.
Removed only obsolete cutoff-specific tests/fixture. Live-thread dependency
retention and unreaped-process evidence remain. Independent concurrency and
operations review approved this change. Server/server-app module tests, source
and dependency checks, and the shell shutdown/reaping test passed. This is a
policy restoration, not a throughput optimization or checkpoint-stall fix.
Evidence: `/Users/blater/src/river/benchmark-results/treebeard-policy-20260914/`.
