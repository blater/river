# CHECKPOINT kernel-hang investigation, 2026-09-13

Owner: [tic-osgiliath](../tickets/tic-osgiliath.md). Shutdown corrections:
[tic-treebeard](../tickets/tic-treebeard.md).

## Confirmed crash evidence

Both retained panic reports contain the same native stack, not merely similar
watchdog messages. Read `processByPid`, `threadById` and `waitInfo`; the textual
`panicString` alone omits the decisive ownership evidence.

| Panic report time | Java PID / thread | launchd waiter | Waiter's lastRunTime |
| --- | --- | --- | --- |
| 13:19:28 | 11854 / 182510 | 183625 | 444.189 seconds |
| 16:53:27 | 1697 / 41723 | 43004 | 437.923 seconds |

Reports are `/Library/Logs/DiagnosticReports/panic-full-2026-09-13-131928.0002.panic`
and `panic-full-2026-09-13-165327.0002.panic` in that same directory.
In each report, launchd's `waitInfo` explicitly names the Java thread as owner of
the kernel read/write lock it is waiting to read. Both Java threads are named
`Java: ForkJoinPool-1-worker-9`, have `TH_WAIT`, `TH_UNINT` and `TH_SFLAG_ABORT`,
and belong to a `terminatedSnapshot` process. The seven-minute figure above is
the waiter's time since last running at the snapshot, not a separately sampled
lock-acquisition timestamp. The reports confirm the prolonged blocked-owner
relationship and unsuccessful termination, not when the first I/O stall began.

The Java kernel frame offsets are identical between reports after normalizing
image indices. Both have an extension frame at offset `0x82fe8` in image UUID
`bd0236ac-207c-3a10-991f-18d2785815af`.

## Native stack interpretation

The installed `/System/Library/Kernels/kernel.release.t8142` has UUID
`447D769E-1CB7-3086-A0B4-32226837B587`, matching both reports. Its standalone
`__TEXT_EXEC` address is `0xfffffe00072ec000`. Add the saved image-zero frame
offsets to that address before symbolication: the collection's relocated text
base is different. Passing that collection base directly as the standalone
Mach-O load address produces misleading nearest-symbol output.

Matching `atos`, exported symbols and short disassembly ranges identify the
Java stack's `VNOP_WRITE`, `cluster_write` and `cluster_write_ext` frames. The
clustered-write frame calls an internal UBC page-list routine before entering
the VM wait. The launchd stack includes `IORWLockRead`, `vnode_iterate` and
`vfs_iterate`. Several private functions remain unnamed in the shipped kernel.
Do not label an unnamed function using its nearest exported symbol.

This narrows the failure to the native filesystem write/VM page path. It does
**not** establish that `MappedByteBuffer.force`, unmap, truncation, or a particular
River file caused it. Apple publishes the relevant clustered-write and UBC
implementations in [vfs_cluster.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/vfs/vfs_cluster.c)
and [ubc_subr.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/ubc_subr.c);
those sources help interpret ownership but are not an exact-source match for
every private function in this installed kernel.

The retained client stack reaches `TpccRunPhase.checkpointAndIdentify` at the
actual `CHECKPOINT` call. It reports `IO_FAILURE`, which does not distinguish a
server I/O status from transport failure. JFR was disabled in that failed run.
The retained evidence directory is
`/Users/blater/src/river-performance-evidence/20260913-da4e/checkpoint-failure-capture/`.

## Historical validation under the subsequently withdrawn timeout policy

The user explicitly authorized Java builds and TPS again with the stop deadline.
Every command below used an external monotonic, absolute 15-second process-group
budget: TERM at 13 seconds, KILL at 14, and failure without blocking reap or cleanup
at 15. This bounds intentional userspace supervision while schedulable; it cannot
make an uninterruptible kernel call return. No command was automatically retried
after reaching that deadline. Builds and workloads were sequential.

GraalVM Java 25.0.4 was selected explicitly. Gradle used `--no-daemon`,
`--no-parallel` and `--max-workers=1`.

- `:river-platform:compileJava`: successful, cached, two seconds.
- `NioMappedFileTest.forceCloseAndReopenPreservesMappedData`: passed; build five seconds.
- `:river-bench:installTps`: successful, two seconds.
- `EmbeddedDatabaseTest.checkpointsStablePagesRotatesWalAndReplaysNewSuffix`:
  passed, including two checkpoint generations, reopen and suffix recovery;
  build six seconds.
- `LoopbackRiverServerTest.shutdownDeadlineRetainsFailureWhileWorkerIsStillAlive`
  and server-app compilation: passed; build eight seconds. This uses a bounded
  synthetic worker, not a kernel-hang reproduction.

TPS evidence is under
`/Users/blater/src/river-performance-evidence/20260913-checkpoint-deadline/`.
Both runs used tiny/standard, serializable, one warehouse, seed 42, one-second
warmup and measurement, server/client heaps 1 GiB/512 MiB, startup limit three
seconds and runner limit eight seconds. JFR was disabled.

- `single-worker`: CHECKPOINT completed, but the original shutdown patch could
  accept a forced server exit. Exclude this sample from clean-run evidence.
- `four-workers`: after correcting forced/nonzero server-exit reporting, passed
  CHECKPOINT and cleanup with zero errors, no forced termination, no remaining
  transactions/locks/waiters. The stop budget was five seconds. Complete console
  output is `four-workers.console.log`; metadata retains the command.
- `java-shutdown-fix`: after the Java join/retention correction and rebuilding
  the distribution, the same four-worker smoke passed CHECKPOINT and normal
  shutdown with zero errors and no forced termination. Full console output is
  `java-shutdown-fix.console.log`.

The complete `LoopbackRiverServerTest` class subsequently passed (nine-second
build including TPS installation), and all three tests in
`RiverDaemonInstanceJdbcIntegrationTest` passed (ten-second build). The latter
proves every worker dependency remains retained after timeout. Independent
Astra source review approved the Java and shell failure-handling scope. The
exact outer supervisor is retained as `command-deadline.py` with the artifacts.

These one-second figures are not performance baselines or proof that the longer
failure is fixed. None of the short runs reproduced the original kernel hang.

## Corrections and remaining work

The shell supervisor previously could wait unconditionally after SIGKILL. The
working patch uses a shared shutdown budget, terminal-child checks, sticky
unresponsive-process failure and retained files. Runtime validation additionally
found and corrected false success after a forced or nonzero server exit.

Java's `LoopbackServerShutdown` had an explicitly unbounded worker join despite
its bounded-shutdown description. The working correction shares its five-second
deadline across joins and latches failure on repeated close. The instance owner
returns before closing database/mappings/identity if server shutdown is not
terminal. Native closes and monitor acquisition remain outside that join bound.

The next diagnostic must identify the specific file, offset and Java write frame
before termination destroys that evidence. Do not replace the mapped provider
or claim a checkpoint-format fix based solely on this kernel stack. The user withdrew the timeout policy on 2026-09-14; the historical guards above
are not current operating instructions or proof of crash prevention.


## Source trace and evidence gap, 2026-09-14

Read-only source inspection and independent review identify direct positional
writes during CHECKPOINT. The TPS server opens its database through EmbeddedRiver;
EmbeddedDatabaseOpener uses NioDurableDirectory even though daemon lifecycle
capabilities use the APFS adapter. The checkpoint sequence is:

1. EmbeddedCheckpoint.commit performs vacuum and table.flush.
2. IndexedCheckpointCoordinator.flush writes dirty live pages, resizes the page
   file, flushes row/version sidecars, forces the page file and directory.
3. IndexedCheckpointWriter writes the next immutable checkpoint page file using
   FileIoMode.POSITIONAL, then truncates, forces, closes and forces its directory.
4. WAL rotation and checkpoint control installation follow before obsolete-file
   removal.

NioDurableFile.write calls positional FileChannel.write. Its resize path can
also extend a file with a one-byte positional write. These calls are concrete
candidates for the saved VNOP_WRITE/cluster_write stack; the native stack does
not distinguish them or identify the target file. Do not attribute the failure
specifically to mapped WAL force.

Independent source review found no proven write/unmap race. Mapped transfer,
force, truncation and close share the mappedData monitor. Checkpoint maintenance
and database shutdown also have transaction/session ownership barriers. These
observations narrow hypotheses; they do not prove every lifetime interleaving
safe or resolve the kernel defect.

The Java thread name ForkJoinPool-1-worker-9 does not identify a CHECKPOINT
operation. River connection handlers, group-commit writer and deferred session
cleanup use virtual threads; the carrier name alone cannot select the mounted
Java operation.

The retained sample.txt says the sampler could not examine Java PID1697 despite
its apparent existence. process-state.txt records it in ?E state at elapsed
04:15. Consequently that capture contains no server Java stack to connect the
native write to a River file. The client stack does identify the actual
CHECKPOINT call. The panic's terminated snapshot cannot establish whether
termination initiated or followed the first kernel stall.

The missing evidence is the mounted Java write stack, target file/vnode or file
descriptor, offset/length and ordering of first blocked I/O against stop/signals.
The existing records establish a severe kernel stall, but not its initiating
River operation. No workload or Java process was launched for this source audit.


## Retained-evidence review during WAL resumption, 2026-09-14

Independent concurrency/operations review rechecked both saved panic records,
the failed sampler and server log against published runtime `5dcee338`. The
Java threads have kernel frames only: no user frames/registers or target file
descriptor, vnode/path or write offset. The sampler retained no server Java
stack, and server.log contains only startup information. Source comparison
confirms EmbeddedCheckpoint, IndexedCheckpointCoordinator,
IndexedCheckpointWriter and NioDurableFile are byte-identical to `ef935596`;
several positional writes and extension writes remain possible. Source order
cannot identify which one stalled.

This closes the bounded retained-data inspection, not the bug. The missing
evidence is the mounted Java write frame, target file and offset/length, and
whether the first stall preceded termination. No causal finding supports a
production change or ticket closure. The current P0 campaign is separate; a
passing checkpoint demonstrates that run's operation, not repair of the
historical kernel stall. No new reproduction or instrumentation was added.
