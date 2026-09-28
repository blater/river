---
id: tic-dorlas
status: parked
type: investigation
priority: 1
assignee: blater
delivery: evidence
tags:
    - checkpoint
    - timeout
    - diagnostic
links:
    - tic-osgiliath
    - tic-emeldir
created: 2026-09-15T03:17:26.927861Z
---
# Investigate CHECKPOINT failure after sustained load and hard-coded client timeout

Determine why the post-load CHECKPOINT request repeatedly disconnects after sustained load. Establish whether CHECKPOINT is still progressing and completes after the client deadline, returns a real server-side I/O failure, or reaches the same host/kernel failure recorded by the existing crash investigation. The existing root-cause owner is [tic-osgiliath](tic-osgiliath.md); retained crash evidence remains in [tic-emeldir](tic-emeldir.md). This is a focused checkpoint follow-up, not a second root-cause stream and not a TPS benchmark dependency.

Known transport evidence identifies a hard-coded 30,000 ms socket read deadline in `river-client/src/main/java/io/riverdb/client/RiverClientConnector.java:18`, applied by `setSoTimeout` at line 75. JDBC network timeout is 30 seconds; Statement query timeout is zero and the current connection setter is unsupported. A read-timeout `IOException` maps to `IO_FAILURE` at the client boundary. Runs 01-05 completed load, preflight, measured work, and drain, then reported missing CHECKPOINT responses at approximately 30 seconds and force-terminated the owned server. Persisted write records do not establish whether a slow valid checkpoint, a server failure, or a host/kernel event initiated the disconnect.

The scope is one representative post-load checkpoint path with evidence sufficient to classify the client failure and its server state. Keep aggregate results and private artifacts in tic-osgiliath and the incident directories. Avoid generic timeout frameworks and broad workload sweeps; a smallest production fix is in scope only if the evidence identifies a specific required owner and mechanism.

## Design

Trace one reproduced CHECKPOINT from client request through server execution and cleanup. Correlate the client deadline with server completion, returned status, connection closure, and owned-process state. Exercise a deliberately slow but valid checkpoint path alongside a real I/O-failure path so the investigation can distinguish a healthy operation that outlives the deadline from a server failure. Compare the resulting evidence with tic-emeldir and tic-osgiliath without assuming that a client timeout caused the historical kernel panic. Native or administrator-only collection is optional evidence, not an automatic prerequisite.

## Acceptance Criteria

Determine the cause of the client failure for a reproduced post-load CHECKPOINT: slow valid completion beyond the deadline, returned server I/O failure, external/process termination, or host/kernel failure. Record the exact timeout owner, default, and configurability from source. Demonstrate the distinction between a slow valid checkpoint and a real I/O failure if a fix is made. If the evidence cannot identify the same kernel root cause, state that explicitly and retain the bounded unresolved result. Keep TPS benchmark behavior and its workload mix outside this ticket’s acceptance contract.
