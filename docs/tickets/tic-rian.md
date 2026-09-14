---
id: tic-rian
status: closed
type: investigation
priority: 1
assignee: blater
parent: tic-primula
delivery: evidence
evidence:
    - docs/tickets/tic-rian.md
tags:
    - performance
deps:
    - tic-gwindor
    - tic-morgoth
created: 2026-09-13T11:46:48.689601Z
---
# Validate transport interoperability and workload benefit

Evaluate the exact integrated source after `tic-gwindor` and
`tic-morgoth` have accepted or explicitly rejected outcomes. Inspect the
actual dispositions, not just closed status. Use the selected contract from
`tic-edoras` and both Java and separately delivered Go consumers.

This evidence-only gate cannot fix protocol or harness code. Require end-to-end
ordering, parameter/result equivalence, cancellation/pressure/streaming and
uncertain-commit fault tests from the implementation; verify server-owned cleanup
and bounded retained handles/bytes. Pin independently versioned consumer artifacts
and record publication status; an unavailable Go delivery blocks cross-consumer
acceptance, not unrelated River diagnostics.

Measure same-family and sample/all matched controls/candidates, using identical
SQL, transaction boundaries, transport/TLS, isolation, retry policy and data.
Report writes/exchanges/bytes per attempt and commit, waits/lock residence,
throughput/p99/retries and truthful failures. Apply the
[shared promotion contract](../plans/performance-three-epics-handover.md); no
speedup based only on request counts, no generic MariaDB parity claim.

### Accepted composed outcome, 2026-09-14

Accepted mechanism: gwindor's binding-owned database/sql catalogue, locally
integrated in river-harness at4ff2a71d with annotated
perf-checkpoint-20260914-prepared-catalogue. Production6c5f655 is unchanged by
final test/documentationebdab46. The user accepts local delivery with no remote.
Morgoth is explicitly rejected without pipelining; it is not shipped. Nimloth's
separate rollback/retry correction is delivered at7acf689a, integrated3d70a826;
it carries no throughput claim and changes no measured Go transaction path.

All eight main-workload samples pass exact attempt accounting, invariants,
zero failed/unknown outcomes, graceful stop and owned-data removal. Identical
sample/all, four workers, one warehouse, seed42, retries20, 5s warmup, River
27033027 production, JVM1GiB, TCP/TLS1.3, READ COMMITTED with explicit FOR UPDATE
and local durable-WAL acknowledgement are retained. At30s, controls520.07/530.11
TPS versus candidates758.97/768.48. Predeclared120s C/A/A/C yields
535.60/691.81/685.50/459.98 TPS; p99 is33.817/26.018/26.313/40.010ms.
Each duration has matching eligible comparison keys. Longer candidate retry/
attempt rates13.27–13.47% are lower than controls13.79–13.87%; higher raw retries
reflect more completed work. Both longer candidates exceed both controls with
lower p99. The final control shows temporal variation, not an identified cause;
no universal percentage improvement or cross-database performance claim is made.

Matched New Order, one worker, 2s warmup/10s measurement improves225.28 to330.90
TPS and p998.110 to5.890ms, with zero retries and passing cleanup/invariants.
The candidate profile covers its whole29.992426s measured window,26,977attempts
and23,109commits. Its784,569observed socket writes equal29.08/attempt and
33.95/commit, versus baseline55.74/65.35. Bytes are192,428,868 total. Candidate
has no sampled SessionEndpoint.prepare frame versus37 baseline; that does not
prove zero preparations. The counting-driver proof establishes actual reuse:
2N prepares/releases for4N executions across two fully pinned worker phases.
Profile/adjacent uninstrumented TPS770.49/770.64 is nearly equal; no precise
profiler-overhead or wait reduction is inferred. Events are selected by start
time; complete unmounted lock/queue waits and message-family counts remain
unavailable and support no pipelining, WAL or wait-time claim.

Final Go uncached full tests and vet pass; full race tests plus the final affected
adapter race rerun pass. The MariaDB common-binding sample/all1worker1s/3s smoke
passes invariants, zero failed/unknown outcomes and authenticated graceful stop,
with lifecycle inactive→inactive. Its timing is not compared with River.
Focused Java retained-plan, resource, authorization/private-DDL, typed-parameter,
JDBC reuse and terminal cleanup checks pass29tests. Benchmark tests including
nimloth pass99 with two opt-in skips; source/module policy checks and installTps
pass. Java unchanged retained-handle behavior and Go tests together cover the
accepted consumer contract, including uncertain COMMIT delivery without replay.
Independent execution_admission_review grants promotion with no remaining gap.

Exact individual reports, configurations, test XML/logs, profiles/readers and
baseline/candidate executables are retained under
/Users/blater/src/river-performance-evidence/20260914-performance-epics/;
see the shared checkpoint ledger for every report ID and accepted source/tag.
