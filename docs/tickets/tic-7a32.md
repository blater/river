---
id: tic-7a32
status: closed
type: story
priority: 1
delivery: code
created: 2026-09-10
owner: root
branch: ticket/tic-7a32-shared-preparation
base-commit: a4567c01b73db452e5ceb0ecda448ca390231284
worktree: /private/tmp/river-shared-preparation
---
# Share repeated statement preparation within a session

The current four-worker full-mix profile attributes 16.9% of request/commit CPU
samples to PREPARE. The external harness retains connection-bound Go statements;
Go's transaction binding prepares those statements again. River currently parses,
captures and validates an identical template for every independent handle.
See the latest [benchmark investigation](../benchmark-log.md).

## Scope

Share exact-SQL immutable prepared templates between live handles in one session.
Keep handle close and transaction-program references independent. Charge retained
SQL, template and lookup storage to the existing budget; release shared ownership
on final close and all ownership on session cleanup. Preserve authorization,
current catalog and private DDL visibility, active-query exclusion, and existing
execution-time schema validation. No process-wide cache, cold template retention,
new setting, workload change, transaction change or weaker durability/isolation.

## Acceptance

Focused tests demonstrate one compilation for repeated eligible preparation,
independent handles and program references, final-close/reopen, bounded pressure
and cleanup, and correct DDL/authorization behavior. Independent review checks
ownership and visibility. Run affected tests, slopmark before/after and a clean
full check. Capture two matched external full-mix JVM controls and candidates;
repeat the profile to establish which preparation work disappeared. Keep profiling
separate from throughput measurements and investigate repeated regressions.
Deliver through merge, annotated performance checkpoint and push, with evidence
in the performance ledger and current local runnable artifacts.

## Controls before implementation

Unchanged `a4567c01` JVM server, GraalVM 25.0.4 with `-Xmx1g`, external harness
`sample all`, four workers, one warehouse, seed42, retries20, warm15/duration30:
313.033 / 338.371 TPS; p99 52.855 / 50.561 ms. Both passed warmup and measurement
with zero failed/unknown outcomes, invariant checks and graceful inactive cleanup.
Commands/results: `/private/tmp/river-tic-7a32/control-{1,2}.log`; compact results
and report paths: `/private/tmp/river-tic-7a32/controls.json`. Versions:
`master-a4567c01-preparation-control-{1,2}`. These samples show host variation;
retain both when judging candidates. Existing same-code CPU/wall baseline:
`/private/tmp/river-tic-5c21/{cpu,wall}.collapsed`.

## Implementation

The existing session statement store now gives each handle a reference to one
budgeted plan/key entry. A local exact-SQL hash directory indexes live published
entries. Old generations stay alive for their existing handles; lookup selects
the newest validation generation. Final close removes the entry and releases its
plan/key charge. Physical handle and lookup capacity remains reusable and charged
until session cleanup. Program references remain per handle.

One SQL validation flow retains cleanup, authorization and atomic schema admission.
An admitted generation match skips parsing, bound-workspace reset, table resolution
and template capture. The immutable preparation generation is separate from the
existing execution recompile token. Private-DDL validation receives generation zero
and never enters the sharing directory, including after rollback.

Slopmark: retained statement owner 20.8375 → 16; new template directory 15.6875;
SQL coordinator 283.768 → 285.853. The coordinator retains its existing preparation
admission responsibility; the directory owns only retained template lookup/lifetime.
Reports: `/private/tmp/river-tic-7a32/slopmark-*.txt`.

## Correctness and review

Implementation `f90eabf9` passed the clean full check/installTps checkpoint in
4m14s (`clean-check.log`). Focused prepared-storage, SQL admission and transaction-
program tests passed. A subsequent focused pressure test admits the compiled plan
but rejects key/entry retention, proving that the reservation is released and an
existing handle remains valid; retry and final cleanup also pass
(`final-focused-tests.log`). Both logs are under `/private/tmp/river-tic-7a32/`.

Independent review checked stale generations, private DDL rollback, schema
admission, authorization, program references and budget failure paths. No blocker
remains. The candidate-plan method is an internal engine service: supported
engine-api, JDBC and protocol surfaces expose only opaque handles, and the
River-owned caller's exact-SQL/lifetime precondition is documented.

## Performance acceptance

Two 30s candidates passed at 377.231 / 377.604 TPS versus 313.033 / 338.371
controls. An adjacent 60s control/candidate pair passed at 371.733 / 389.817 TPS,
with p99 44.237 / 41.878 ms. The longer observed gain is 4.9%, substantially
smaller than the short pair's 15.9%; keep the duration/host variation visible.
All runs passed invariants, zero failed/unknown outcomes and graceful cleanup;
retry rates per commit improved. Detailed commands and individual samples are
in the performance ledger and `/private/tmp/river-tic-7a32/samples.json`.

The separate profile reduced PREPARE from 16.895% to 3.195% of selected request/
commit CPU samples (wall 16.583% to 3.659%); template capture had no samples.
The profile run passed correctness and cleanup. Accept the mechanism and local
JVM improvement, with no precise general or native speedup claim.

One adjacent repetition was removed inside this owner: resolving a handle without
a requested query kind now looks it up once. Remaining socket-write, FK discovery
and execution-workspace costs stay outside the ticket.

## Delivery

The unchanged approved O3/PGO native build passed in 1m56s (`native-build.log`).
The actual executable passed a one-worker sample/all smoke through authenticated
clients, post-run validation and public server shutdown (`native-smoke.log`).
This is functionality evidence; native throughput was not compared.
Checkpoint: `perf-checkpoint-20260910-shared-preparation`.
