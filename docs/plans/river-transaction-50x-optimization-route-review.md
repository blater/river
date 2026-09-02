# Lead-integrator review: transaction 50x optimisation route

Status: conditionally approved for implementation after the amendments below
Reviewer: lead integrator
Review date: 2026-09-02
Scope: `docs/plans/river-transaction-50x-optimization-route.md`

## Executive finding

The route has the right architectural direction: it removes avoidable
request/response turns, reuses point-native storage operations, keeps row-set
and aggregate work on their appropriate paths, and makes durability and
contention part of the measurement rather than hiding them behind a faster
client facade. It is practical as a staged vertical slice.

The “50x” number is not yet a valid acceptance criterion. The route combines
different denominators—protocol requests, single-transaction latency, and
Alpha3 mixed-workload throughput—and the current evidence does not establish
that they can be multiplied into one result. Implementation may proceed, but
promotion requires the explicit contracts and gates in this review. The 50x
figure should remain an aspirational composition target until a paired,
same-contract benchmark demonstrates it.

The route also describes some work as if it is still unwired. The
`PREPARE_PROGRAM`, `EXECUTE_PROGRAM`, and `CLOSE_PROGRAM` dispatch path already
exists through `SessionEndpoint`, `RiverSession`, the embedded engine, and the
remote client. The first implementation task is therefore semantic hardening,
benchmark integration, and missing coverage—not a second protocol plumbing
implementation.

## What is sound

- The one logical program execution is a meaningful boundary. It can reduce
  client/server turns while retaining one engine transaction and one commit.
- The route explicitly preserves durability, isolation, retry, cardinality,
  and bounded-result requirements. That is the correct constraint for a
  database optimisation.
- Point lookup, row-set, and aggregate work are distinguished. A point path
  must still recheck predicates and enforce `EXACT_ONE` versus `ZERO_OR_ONE`.
- Typed result publication is a useful ownership boundary. It can eliminate
  intermediate row objects for singleton results while leaving bounded block
  encoding for row sets.
- Reusing the existing WAL group coordinator is the correct durability shape.
  A program-specific force path would make the comparison and recovery
  contract less trustworthy.
- The route’s layered evidence plan (mechanism, embedded path, protocol path,
  workload, and concurrency/durability) is appropriate. No single TPS number
  should be allowed to stand in for all five layers.

## Findings requiring resolution

### 1. Define the claim and denominator before implementation [blocker]

The route currently asks whether “50x” means one-terminal average, New Order,
the mixed transaction profile, or the Alpha3 1,000-TPS objective. These are
different claims. The baseline also contains a 28.8-request/commit protocol
ratio and a 4.11 ms sample WAL force; neither is a throughput multiplier.

Resolve it as follows:

1. **Protocol claim:** a warmed program attempt has one logical execute request
   and one logical response. A response may contain continuation frames; report
   both logical exchanges and physical frames/bytes.
2. **Mechanism claim:** compare the same bound operation through scan and point
   paths in an embedded or loopback harness. Report latency, allocations,
   copied bytes, and path counters; do not convert this directly to TPS.
3. **Capacity claim:** the user-visible target is Alpha3’s 1,000 committed
   TPS under its declared five-family mix, workload schedule, durability,
   isolation, error budget, latency budget, and scale. This is an engineering
   target, not an official TPC-C result.
4. **50x claim:** if retained in the title, it is an aspirational composition
   target. It becomes a pass/fail claim only when the lower bound of a paired
   confidence interval for the declared primary ratio is at least 50x, with
   unchanged correctness and durability contracts.

The one-terminal durable-latency result must be reported separately. Group
commit cannot amortise a force with a group of one, and the cited 4.11 ms force
sample is inconsistent with a claimed 50x local-durable single-terminal
latency improvement unless the durability tier or hardware changes.

### 2. Prevent result encoding from discovering overflow after commit [blocker]

`TransactionProgramExecutor` commits before the server response is encoded.
`ProtocolProgramResultEncoder` can then discover that the logical result is
larger than the maximum response, return `RESOURCE_EXHAUSTED`, and leave the
client without a definitive result even though the transaction committed.
Physical continuation frames do not solve logical response overflow.

The implementation contract is:

- Reserve or incrementally account for encoded result bytes while each step
  publishes its result. Include framing metadata and the protocol’s maximum
  logical response size.
- If the logical result cannot fit, return `RESOURCE_EXHAUSTED` before commit
  and roll back the whole program.
- Keep physical fragmentation as a transport concern after logical admission;
  a fitting result may use multiple continuation frames.
- Make the result budget bounded and observable: maximum, current, rejected
  bytes, and the step that caused rejection.

Add a test that executes a mutating program whose result crosses the logical
limit and verifies no mutation is visible. Add a separate test proving that a
large-but-fitting result is fragmented, reassembled, and committed exactly
once.

### 3. Make isolation, retry, and business rollback explicit [blocker]

The program coordinator currently begins `SERIALIZABLE`, while the JDBC
baseline defaults to `REPEATABLE_READ`. Comparing those paths without declaring
the difference is not an apples-to-apples performance result and can change
both lock behaviour and group-commit eligibility.

For the first slice, choose one of these concrete contracts and document it in
the API and benchmark report:

- expose isolation in the program invocation and run baseline and candidate at
  the same level; or
- fix the initial program contract at `SERIALIZABLE` and configure the
  canonical baseline identically.

Do not silently downgrade isolation to make the candidate faster.

Retry rules also need a terminal-outcome contract. Retry only a
server-confirmed retryable status such as serialization/deadlock conflict.
After transport loss or a lost response following commit, the outcome is
indeterminate: do not automatically replay the program. Fence the session or
return an explicit indeterminate status that the caller must resolve.

TPC-C includes an expected business rollback for an invalid item. The current
program action/result model has no explicit business-abort outcome, and the
existing retry helper only retries SQL state `40001`. Define a program-level
business-abort result (or an equally explicit workload adapter contract), and
preserve the invalid-item case and its rollback accounting. Do not turn a
business rollback into a generic failed attempt or remove it from the mix.

Finally, define cancellation: closing the client connection while the server
is executing does not necessarily cancel a lock wait or prevent a commit. Add
a timeout/cancellation test and document whether the guarantee is immediate
cancellation, eventual cancellation before commit, or fencing with an
indeterminate outcome.

### 4. State exactly when group commit applies [high]

“All program commits enter the group path” is not true for the current engine.
The existing eligibility predicate excludes serializable/range-protected
scans, active scans, tuple lifecycle work, and lock conflicts; read-only
transactions do not need a WAL force. The coordinator also has a direct
fallback for requests that fail group preflight or admission.

Make the claim precise:

- write-bearing, point-only programs that satisfy the existing eligibility
  predicate enter the group path;
- row-set, ordered, aggregate, range-protected, or otherwise ineligible
  programs use the direct path and are reported as such;
- read-only programs are reported separately with no WAL force;
- no program path bypasses the existing durability ordering or adds an
  unbounded queue.

Instrument and report groupable commits, direct fallbacks, read-only commits,
cohort size, queue wait, force count, force time, append time, and WAL failure
status. Test one terminal, concurrent point writers, an ineligible scan, a
lock waiter, group preflight failure, force failure, and recovery after restart.

### 5. Choose the benchmark integration boundary [high]

The existing JDBC program extension requires an idle auto-commit connection,
and the JDBC prepared-statement handle is not exposed for constructing a
`TransactionProgram`. The TPC-C terminal currently owns an explicit JDBC
transaction (`autoCommit=false`). A benchmark cannot claim to test the new
program path by quietly using private handles or by changing only one side’s
transaction setup.

For the first vertical slice, use a bench-owned adapter over the public
`RiverClientConnection`/`RiverSession` program API for both baseline and
candidate, or add a deliberate public JDBC extension that exposes preparation
and program construction as one coherent contract. Keep ordinary JDBC as a
separate acceptance path. Do not use reflection or duplicate a private
prepared-plan representation.

The benchmark report must identify the client boundary, transport mode,
serialization mode, and whether preparation is inside or outside the measured
interval. Preparation and close belong outside the steady-state interval, but
their cost must be reported separately.

### 6. Give physical classification a stable home [high]

The route requires `COMMAND`, `POINT_PRIMARY`, `POINT_UNIQUE`,
`SCAN_SINGLETON`, `ROW_SET`, and `AGGREGATE`, but retained prepared plans
currently do not carry this physical metadata. Define whether classification is
stored as immutable plan metadata or derived after restoring and binding the
plan for the current catalog generation.

The recommended first implementation is to derive it after binding, without
retaining a session-bound `BoundSqlStatement` in the long-lived program handle.
Pin one catalog generation for a program invocation. If recompilation changes
the classification, either use the existing recompile semantics before
execution or fail/retry the whole program on a generation conflict. Test stale
handles, catalog change between prepare and execute, and classification change
after recompile.

### 7. Separate point-eligible families from the real workload [high]

Not every TPC-C family is a point operation. Customer-last-name lookup is
nonunique; Delivery selects the oldest order with ordering and locking; Stock
Level is an aggregate; and some Payment/Order Status branches are scans or
ordered work. These must not be forced through a point fast path for a headline
number.

The family matrix must label each statement as point, singleton scan, row set,
ordered/range, aggregate, or command. For every point candidate, verify index
selection, predicate recheck, current-row locking, cardinality, missing-row
status, and projection parity against the existing path. Report family-level
commits, retries, rollbacks, failures, latency, and physical-path counters.

### 8. Make the benchmark capable of producing trustworthy evidence [high]

The current `tools/tps-test.sh` run on 2026-09-02 did not reach the measured
interval: the acceptance preflight failed with `RESOURCE_EXHAUSTED` while
preparing a statement, and the summary printed zero commits and zero TPS.
This is a harness/system-health observation, not performance evidence. The
route’s older close-path failure description should be refreshed after the
current failure is diagnosed.

Before using the runner for a promotion decision, change it to:

- emit `preflight_failed` rather than a valid-looking `tps=0` result when the
  measured phase never starts;
- record exact status codes and phase (`load`, `preflight`, `warmup`,
  `measured`, `drain`, `checkpoint`);
- report started, completed, failed, retried, rolled-back, and still-in-flight
  attempts at the cutoff, with an explicit drain policy;
- report logical protocol exchanges separately from physical frames and both
  request and response bytes;
- provide p50, p95, p99, p99.9, maximum, and raw histogram artifacts rather
  than only bucket upper bounds;
- capture client and server CPU/allocation evidence separately, plus the
  route’s copy, text-pass, row/cell, WAL, lock, and result-budget counters;
- control or separately capture server JFR. A client-only JFR cannot support
  a server hot-path attribution claim;
- run at one and ten terminals and at increasing scale, with local durable
  storage and any quorum/replicated tier reported as separate configurations.

Use at least five interleaved baseline/candidate samples on the same host,
JDK, build, database image, seed, scale, isolation, durability tier, and
scheduling policy. Report the paired ratio and confidence interval. Run
instrumented profiles separately from the canonical uninstrumented throughput
comparison and quantify their overhead.

Record commit SHA, clean/dirty state, compiler/JDK, CPU topology, storage
device, filesystem, page/cache state, database seed, terminal count, offered
load, workload mix, and all flags. A dirty checkout or an unexplained
preflight failure blocks promotion of a performance result.

## Resolved implementation contract

The following decisions make the route implementable without inventing a
legacy bridge:

| Area | Contract for the first slice |
| --- | --- |
| Program boundary | Prepare once; execute one logical program request; execute all steps in one engine transaction; commit once; close outside the steady-state interval. |
| Isolation | Candidate and canonical baseline use the same explicitly reported isolation level. Initial recommendation: configure both at `SERIALIZABLE` until the program API exposes a selectable level. |
| Result lifetime | The result arena owns typed values until encoding completes. No result buffer is reused before the response is sent or copied into an explicitly owned continuation assembly. |
| Result admission | Encoded logical size is bounded before commit. Physical continuation is allowed only after logical admission. Overflow rolls back. |
| Physical path | Classification is immutable per bound invocation/catalog generation. Point paths are used only when their key, predicate, cardinality, and lock contracts are proven. |
| Commit path | Eligible write programs use the existing bounded group coordinator; ineligible and fallback commits are direct and counted; read-only commits are separate. |
| Retry | Only confirmed retryable server outcomes are replayed. Transport loss after execution fences the session and is never blindly replayed. |
| Business outcome | Expected TPC-C invalid-item rollback is represented explicitly and included in rollback/family accounting. |
| Benchmark API | Use public River program APIs through a bench adapter, or add one explicit public JDBC program contract. No private-handle or reflection path. |
| Acceptance | Alpha3’s stated 1,000 committed TPS is the capacity gate; 50x is an aspirational ratio until its denominator and confidence gate are met. |

## Test and evidence gates

The route’s existing L1–L5 structure is retained, with these additions:

1. **Protocol and lifecycle:** codec round trips; one logical exchange;
   continuation reassembly; stale/closed handles; catalog-generation change;
   malformed graph/arguments; exact status propagation.
2. **Execution semantics:** point versus scan parity for every supported
   predicate; `ZERO_OR_ONE` and `EXACT_ONE`; current-row locks and predicate
   recheck; all-null and variable-width values; row-set and aggregate paths;
   expected business rollback.
3. **Transaction safety:** isolation parity; whole-program rollback on any
   step failure; retry only on confirmed retryable status; lost response after
   durable commit; timeout/cancellation and fencing.
4. **Resource and durability boundaries:** result overflow before commit;
   continuation at the frame boundary; bounded result arena/queue/lock state;
   group admission/direct fallback; WAL append/force failure; restart and
   recovery visibility.
5. **Performance:** mechanism tests, embedded tests, loopback protocol tests,
   one-terminal and multi-terminal workload tests, and contention tests. Each
   result includes latency percentiles, exact outcomes, allocations, copies,
   WAL/lock counters, and the full run manifest.

The two focused checks already run for this review—
`EmbeddedTransactionProgramTest` and `ProtocolTransactionProgramCodecTest`—pass.
They do not yet cover the overflow, lost-response, isolation, business-abort,
group-fallback, or Alpha3 program-workload gates above.

## Implementation recommendation

Proceed in this order:

1. Amend the route and benchmark manifest with the denominators, isolation,
   durability tier, integration boundary, and preflight failure semantics.
2. Add result admission and the program outcome/retry contract before changing
   the fast path. This prevents a faster path from creating an indeterminate
   commit or silently changing TPC-C rollback semantics.
3. Implement bound-invocation physical classification and point execution,
   reusing the existing point semantic checks and retaining scan/aggregate
   fallbacks.
4. Add typed publication and encoding counters, then verify ownership and
   allocation behaviour at the embedded and protocol layers.
5. Integrate the existing group coordinator with explicit eligibility and
   fallback reporting.
6. Repair the runner’s phase/status/manifest output, diagnose the current
   preflight `RESOURCE_EXHAUSTED` failure, and only then run paired capacity
   experiments.

This is a viable optimisation route once those gates are made contractual. It
should be judged as a sequence of independently falsifiable improvements that
protect database semantics, not as a promise that four mechanisms sum to a
single 50x result.
