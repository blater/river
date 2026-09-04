# River transaction 50x optimisation route

Status: Conditionally approved for implementation after review amendments

Audience: relational/execution, protocol/client, storage/WAL, transaction,
benchmark, and performance reviewers

Related plans:

- [Current TPS diagnosis and implementation priority](../perf_review.md)
- [River Performance Review and Benchmark Plan](river-performance-review-and-benchmark-plan.md)
- [Alpha 3 TPC-C Schema and Capacity Plan](alpha3-tpcc-capacity.md)
- [River Engineering Personas and Performance Charter](river-engineering-personas-and-performance-charter.md)
- [Lead-integrator review and findings](river-transaction-50x-optimization-route-review.md)

The lead-integrator review is accepted as part of this plan. In particular,
the four steps below are optimisation work inside an already wired program
protocol; they are not permission to add a parallel protocol or to treat a
single TPS figure as proof of a 50x result.

`docs/perf_review.md` is the controlling implementation priority. It applies
the techniques catalogued in `tcpc-perf-notes.md` to current measurements:
canonical lock and hot-resource ordering is P0, and shortening the serialized
prepared-commit/durability window is P1. The protocol, singleton, encoding, and
row-set work described below follows those gates. Generic buffer-pool, index,
page-layout, worker, checkpoint, partitioning, and NUMA changes require their
named evidence and are not implicit prerequisites of this route.

## 1. Decision in one page

The route toward a 50x transaction improvement is four coupled changes,
delivered in this order:

1. **Harden and measure the collapsed transaction protocol.** Prepare a
   transaction program once, then execute the complete transaction with one
   logical request/response. The program protocol is already wired through the
   server and client; the implementation work is semantic hardening,
   admission, lifecycle coverage, and benchmark integration. The server owns
   the transaction boundary and commits before acknowledging success.
2. **Make singleton steps point-native.** `EXACT_ONE` and `ZERO_OR_ONE`
   prepared query steps use an indexed point lookup and a reusable point-result
   carrier. They do not create a general scan cursor, call `nextScan`, or pass
   through a generic row publisher.
3. **Fuse typed publication and wire encoding.** Commands, point results, row
   sets, and protocol encoding share one validated typed-value ownership layer.
   A value is copied only when its lifetime requires a new owner. The genuine
   `ROW_SET` path remains block/vector-oriented.
4. **Batch durability and expose contention.** Program commits enter the
   existing bounded WAL group-commit path. Lock wait, queue, timeout, deadlock,
   and escalation data become explicit so contention is not mistaken for CPU
   cost.

The first step is the largest lever. The next two remove the server-side work
that becomes visible after protocol collapse. The fourth prevents a per-
transaction force or an unobserved lock queue from becoming the new ceiling.

The four steps do not multiply into a guaranteed 50x improvement. Protocol,
mechanism, and capacity claims use separate denominators defined in section
3. The promotion target is Alpha3's declared 1,000 committed TPS under its
specified workload, isolation, durability, latency, and error contracts;
50x remains an aspirational composition target until a paired benchmark
establishes a declared ratio with a confidence interval.

This is an execution plan, not permission to weaken durability, isolation,
cardinality, retry, or bounded-resource contracts. A 50x result must be
demonstrated with the named durability tier and the same correctness workload.
Resource use is governed by configured memory, page, scratch, and concurrency
budgets with explicit backpressure or failure outcomes. Prototype convenience
is not a reason to add a low row, byte, cardinality, or transaction cap; every
operator must retain a scale path from its first implementation.

## 2. Evidence and current constraints

### 2.1 Observed baseline

A prior successful one-terminal River engineering run, using the tiny
non-standard TPC-C profile, measured:

| Measurement | Observed value | Meaning |
| --- | ---: | --- |
| Committed throughput | 42.3 TPS | 846 commits in 20 seconds |
| Protocol requests | 24,342 | 28.8 requests per committed transaction |
| New Order requests | 19,389 | 48.2 requests per attempt |
| Payment requests | 3,646 | 10.1 requests per attempt |
| Delivery requests | 648 | 27.0 requests per attempt |
| Order Status requests | 531 | 16.1 requests per attempt |
| Stock Level requests | 128 | 4.0 requests per attempt |
| Whole-transaction retries | 0 | No retry cost in this run |
| Worker allocation | 37,611,176 bytes | Approximately 44.5 KiB per commit |

The run is an engineering measurement, not an official TPC-C result. The
request counts are the strongest evidence for the first optimisation: the
transaction is paying for many ordered request/response cycles even though its
statements are already prepared and the terminal reuses its connection.

A separate single-update trace measured the following transaction-scoped
operations:

| Operation | Wall time | Client CPU | Protocol requests |
| --- | ---: | ---: | ---: |
| `execute_update` | 3.450 ms | 0.666 ms | 2 (`BEGIN` + execute) |
| `commit` | 5.756 ms | 0.294 ms | 1 |
| Update plus commit | 9.206 ms | 0.960 ms | 3 |

The corresponding server trace recorded a 4.11 ms WAL `FileForce`. This is a
single-update latency sample, not a claim that every TPC-C transaction has the
same force time.

The current checkout's real `tools/tps-test.sh` run fails during preflight with
`RESOURCE_EXHAUSTED`, before the measured interval starts. This is a
benchmark/system-health observation, not performance evidence. The runner
must report `preflight_failed` with the exact phase and status instead of
emitting a valid-looking zero-TPS result. A profile of the failed setup is not
a transaction profile. The older `INVALID_EXTERNAL_INPUT` close-path failure
description is superseded by this current observation and must not be used as
the next diagnosis without reproducing it.

### 2.2 Existing implementation seams

The current tree already contains the intended program protocol end to end.
The implementation must treat it as a semantic contract and add the missing
coverage rather than introduce another dispatch or client lifecycle:

- `TransactionProgram`, `TransactionProgramAction`,
  `TransactionProgramResult`, and `TransactionProgramExecutor` provide a
  frozen program graph, primitive arguments, ordered step results, and an
  explicit begin/commit execution shell.
- `ProtocolMessageType`, protocol codecs, `SessionEndpoint`,
  `RiverSession`, the embedded engine, and the remote client carry
  `PREPARE_PROGRAM`, `EXECUTE_PROGRAM`, and `CLOSE_PROGRAM` end to end.
  Existing wiring is not a substitute for malformed-input, stale-handle,
  lifecycle, resource, and outcome tests.
- `SqlPointSelectExecution` already has a direct primary-key/unique-key lookup
  for some ordinary prepared SQL.
- `TransactionProgramSteps.executeQuery` currently opens a generic prepared
  scan and calls `nextScan` for singleton program actions. This is the primary
  row-at-a-time seam: the program path does not automatically inherit the
  ordinary SQL point fast path.
- `TransactionProgramResult` owns a typed value arena, while
  `SqlExecutionResult`, `SqlScanRowResult`, `HeapRowResult`, and protocol
  result writers represent other value lifetimes. The implementation should
  remove unnecessary crossings between these representations without making
  storage pages or response buffers illegally borrowed.
- `ProtocolProgramResultValueCodec.write` computes text byte width and then
  walks text again to emit it. That is a concrete post-protocol-collapse CPU
  target for text-bearing results.
- `IndexedHybridCommitGroup` and `LocalWal.forceAppendFile` already provide
  the storage-side group-force seam. The performance work must prove that
  program commits use it and do not create a new per-program force path.

## 3. Cost model and success definition

Treat one transaction's service cost as:

```text
Ttransaction =
  ordered request/response waits
  + server admission/dispatch/decode
  + plan binding and scalar evaluation
  + point or row-set execution
  + result publication and value copies
  + response encoding/decoding
  + lock waits
  + WAL append and durable force
```

Step 1 attacks the first two terms. Steps 2 and 3 attack the middle terms.
Step 4 attacks the last two and makes their contribution measurable.

Use three separate denominators:

- **Protocol:** a warmed program attempt has one logical execute request and
  one logical response. Report physical continuation frames and bytes
  separately; they are not additional logical exchanges.
- **Mechanism:** compare the same bound operation through scan and point paths
  in embedded and loopback harnesses. Report latency, allocations, copied
  bytes, and path counters. Do not convert this directly into TPS.
- **Capacity:** the user-visible target is Alpha3's 1,000 committed TPS under
  its declared five-family mix, workload schedule, durability, isolation,
  error, latency, and scale contract. This is an engineering target, not an
  official TPC-C result.

The “50x” title is therefore an aspirational composition target. It becomes a
pass/fail claim only when a paired, same-contract benchmark reports a declared
primary ratio whose lower confidence bound is at least 50x, without changing
correctness or durability. The one-terminal durable-latency result remains a
separate result: group commit cannot amortise a force with a cohort of one,
and the observed 4.11 ms force must not be treated as a throughput multiplier.

Every stage reports all of the following beside TPS:

- committed transactions, attempts, retries, aborts, and exact failure codes;
- logical protocol exchanges, physical frames, and request/response bytes per
  attempt and per commit;
- server/client CPU time and sampled stacks;
- River-owned allocations, retained bytes, copied bytes, and copy boundaries;
- point lookups, scan opens, rows/cells decoded, result cells published, and
  encoded cells;
- WAL append/force count, bytes, group size, force latency, and durability
  acknowledgement time;
- lock acquisition, queue, wait, timeout, deadlock, cancellation, and
  escalation data.

## 4. Cross-cutting contracts

### 4.1 No duplicate executor

There must be one relational semantic executor. The implementation may split
the physical operation selected for a step, but it must not create a second
parser, binder, constraint evaluator, lock protocol, or transaction executor
with subtly different behaviour.

The intended shape is:

```text
frozen prepared plan
        |
        +-- COMMAND       -> command executor -> typed result sink
        +-- query cardinality policy
              |
              +-- POINT_PRIMARY/POINT_UNIQUE
              |       -> point executor -> singleton result sink
              +-- SCAN_SINGLETON
              |       -> scan executor -> singleton/empty result sink
              +-- ROW_SET
                      -> block/vector executor -> row-set result sink
                                                        |
                                                shared typed carrier
                                                        |
                                           shared protocol result encoder
```

`EXACT_ONE` and `ZERO_OR_ONE` are semantic cardinality policies, not physical
operators. The dispatcher first applies the policy and then selects
`POINT_PRIMARY`, `POINT_UNIQUE`, `SCAN_SINGLETON`, `ROW_SET`, or `AGGREGATE`.
Thus a non-unique or otherwise non-point singleton query remains a scan while
still enforcing singleton cardinality. `AGGREGATE` uses its aggregate result
sink and is not routed through the singleton carrier.

The point executor should reuse the current point lookup, projection, and
locking machinery by extracting a common internal operation, not by copying
`SqlPointSelectExecution` into `TransactionProgramSteps`.

### 4.2 Ownership and lifetime

The following ownership rules are mandatory:

| Value | Owner and validity | Permitted hand-off |
| --- | --- | --- |
| Storage tuple bytes | Storage/page owner; valid while the page pin/read result is held | Read by projection or copied into a result-owned carrier before release |
| Projected fixed values | Execution lane or point carrier | Passed synchronously to the result sink |
| Projected text | Execution scratch or carrier owner | One copy into the next owner unless the next owner stores the same encoded representation |
| Program result | `TransactionProgramResult` invocation owner | Encoded synchronously; client receives a distinct response buffer |
| Response bytes | Server response buffer until socket write completes | Never retained by a later request or result object |
| Client program result | Client result owner | Reused only after caller-visible result lifetime ends |

Zero-copy means the owner and lifetime are explicit. It does not permit a
response or JDBC result to alias a mutable storage page.

### 4.3 Error and retry semantics

- Internal expected outcomes remain `StatusCode` results with reusable detail
  carriers. They do not use exceptions for cardinality, conflict, retry,
  resource pressure, or I/O control flow.
- A failed program step reports its program step index, primary status,
  rollback status, and whether the session is fenced.

The program result adds an explicit outcome field so expected business control
flow is not overloaded onto an error status:

| Outcome | Wire status | Retryable | Fenced | Commit state |
| --- | --- | ---: | ---: | --- |
| `COMMITTED` | `OK` | no | no | durable commit acknowledged |
| `BUSINESS_ROLLBACK` | `OK` plus outcome | no | no | whole program rolled back |
| `STEP_FAILURE` | primary failure status | status-defined | no unless rollback fences | whole program rolled back |
| `RETRYABLE_CONFLICT` | `CONFLICT` or `DEADLOCK` | yes | no | whole program rolled back |
| `CANCELLED_BEFORE_COMMIT` | `CANCELLED` or `TIMEOUT` | no | no | whole program rolled back |
| `INDETERMINATE` | `FENCED` | no | yes | commit unknown to caller |
| `PROGRAM_STALE` | `PROGRAM_STALE` | no | no | transaction not started |
| `RESULT_OVERFLOW` | `RESOURCE_EXHAUSTED` | no | no | whole program rolled back |

`PROGRAM_STALE` is a new stable, non-fatal, non-retryable status in the
invalid-input family, using the next unused stable code `3015` without
renumbering existing protocol statuses. `BUSINESS_ROLLBACK` is an explicit
result outcome with `OK` wire status because it is an expected TPC-C business
decision, not a failed request. The benchmark counts it separately from
committed, failed, and retryable attempts.

- Candidate and canonical baseline use the same explicitly reported isolation
  level. Until the program API exposes a selectable level, configure both at
  `SERIALIZABLE`; do not silently downgrade isolation to improve the number.
- The client retries the whole transaction only after a server-confirmed
  retryable status such as serialization/deadlock conflict. It must not retry
  individual program steps after the server has begun the transaction.
- A transport loss or lost response after execution may have followed a
  durable commit. It is `INDETERMINATE`, must not be blindly replayed, and
  fences the session. The caller must resolve it through the existing
  recovery/status mechanism.
- `EXACT_ONE`, `ZERO_OR_ONE`, and `ROW_SET` remain observable semantic policies;
  a fast path must return the same result for zero, one, and too-many rows.
- TPC-C's expected invalid-item business rollback uses
  `BUSINESS_ROLLBACK`, is included in rollback and family accounting, and is
  not eligible for conflict retry.
- Cancellation is guaranteed only before the commit gate. The server checks
  cancellation at step boundaries and immediately before entering commit; an
  observed request returns `CANCELLED_BEFORE_COMMIT` and rolls back. Once the
  commit gate is entered by transferring the transaction to the coordinator's
  `PREPARED` ownership state. Generic session abort and thread interruption do
  not cancel that work; the caller awaits its exact outcome. The coordinator
  alone may abort it before `COMMITTING`. After the gate, cancellation cannot
  undo the commit: the server
  completes the durability contract, and a lost response produces
  `INDETERMINATE`/`FENCED` rather than a replayable cancellation.
- A program cannot acknowledge commit before the selected durability contract
  is satisfied.

### 4.4 Bounds and backpressure

- Program graphs, argument arenas, result metadata, result values, encoded
  responses, WAL cohorts, and lock queues remain bounded by existing resource
  plans or an explicitly reviewed extension.
- Result admission happens before commit. While each step publishes, reserve
  or incrementally account for the complete logical result—program-result
  metadata, value framing, and encoded values—against the maximum logical
  response size. Physical frame and continuation overhead is accounted for
  separately in the bounded response workspace. If the logical result cannot
  fit, return `RESOURCE_EXHAUSTED` and roll back the whole program.
- Physical continuation frames are a transport concern after logical result
  admission. A fitting logical result may be fragmented and reassembled;
  fragmentation must not allow a committed-but-undeliverable result.
- Expose result-budget maximum, current usage, rejected bytes, and the step
  that caused rejection. A transaction program must not reserve a
  maximum-size result for every possible row set.
- A full response or WAL group returns `RESOURCE_EXHAUSTED` or `RETRY` under
  the existing distinction; it does not grow an unbounded queue.

## 5. Step 1 — Collapse the transaction protocol

### 5.1 Current path

The TPC-C terminal reuses prepared statements, but each statement still uses
the ordered client exchange path. `RiverClientExchange.exchange` writes and
flushes one frame and then waits for one response. A transaction therefore
repeats client encode, socket write, server frame admission, dispatch, SQL
execution, response encode, socket read, and client decode for each statement.

`TpccTerminal.executeOne` already measures request deltas around a whole retry
attempt. That metric should remain the end-to-end guard while program execution
is introduced.

### 5.2 Target contract

Use and harden the existing connection-owned program lifecycle:

1. Prepare the individual SQL plans during terminal setup.
2. Build a frozen `TransactionProgram` containing the plan handles, action for
   each step, parameter expressions, control-flow guards, and captured output.
3. Send `PREPARE_PROGRAM` once per connection/program family. The server retains
   references to the prepared plans and returns a generation-safe program
   handle.
4. For each transaction, populate a reusable primitive argument arena and send
   one `EXECUTE_PROGRAM` request.
5. The server validates arguments, begins the explicit program transaction,
   executes steps in order, commits once, and returns ordered step results.
6. The client consumes the result and reuses the argument/result arenas only
   after the response is complete.
7. Close the program outside the measured interval.

The measured wire sequence for a warmed transaction should be one execute
request and one response. Begin and commit are engine operations inside that
request, not additional wire round trips. Program prepare/close are setup
costs and must be reported separately.

### 5.3 TPC-C program mapping

The first consumer should cover one transaction family end to end, then the
remaining families. A proposed action classification is:

| Family | Typical step shapes | Physical classification | Program result policy |
| --- | --- | --- | --- |
| Payment | Warehouse/district/customer point reads, updates, history insert | Point and command | `EXACT_ONE`, `COMMAND` |
| New Order | Warehouse/district/customer/item/stock point reads, several updates/inserts | Point and command | `EXACT_ONE`, `COMMAND` |
| Delivery | Candidate order lookup plus updates | Ordered/range and command | `ZERO_OR_ONE`, `COMMAND` |
| Order Status | Customer/order/order-line reads | Point, singleton scan, or row set | `ZERO_OR_ONE`, `ROW_SET` only where genuinely multi-row |
| Stock Level | Aggregate or multi-row stock query | Aggregate or row set | Aggregate/row-set path; do not force it through singleton logic |

The exact SQL and result captures belong in the workload adapter. The engine
must receive a frozen validated program and must not contain TPC-C-specific
branches. The benchmark report must retain this family-level classification;
non-point families must not be removed or forced through a point path for a
headline result.

### 5.4 Protocol and server work items

- Verify the existing `PREPARE_PROGRAM`, `EXECUTE_PROGRAM`, and
  `CLOSE_PROGRAM` dispatch, response buffering, and client lifecycle through
  tests and the benchmark. Do not add a second protocol plumbing path.
- Keep one ordered request ID and one response validation path.
- Reserve or reuse the request/response buffers before sending. Do not allocate
  a new byte array for each argument or step.
- Validate program handle, program generation, argument count, descriptors,
  text lengths, action policy, and response shape at the boundary.
- Make program handles stale-safe. Closing a program must release plan
  references and retained graph/result capacity exactly once.
- On every execute, compare the program's pinned catalog generation with the
  current generation before `beginProgram`. On mismatch, return the new
  non-retryable `PROGRAM_STALE` status, do not begin a transaction, and require
  the client to close and prepare a fresh program. There is no implicit
  rebind/recompile in the measured execute path, and a stale program is never
  executed. The stale handle remains closeable but is not executable.

### 5.5 Benchmark integration boundary

The first TPC-C program consumer uses a bench-owned adapter over the public
`RiverClientConnection`/`RiverSession` program API. It must not use reflection,
private prepared-statement handles, or a duplicate private plan
representation. Ordinary JDBC remains a separate acceptance path.

Preparation and program close are outside the steady-state transaction
interval, but their time, requests, bytes, and failures are reported in the
setup section of the run manifest. The measured section identifies transport
mode, serialization mode, isolation, durability tier, program family, and
whether execution is embedded or loopback.

The adapter owns the workload mapping and reusable argument/result arenas. The
engine receives only a frozen validated program and primitive argument values;
it must not acquire TPC-C-specific branches.

### 5.6 Step 1 acceptance gates

Functional:

- A simple prepared update executes and commits through one `EXECUTE_PROGRAM`
  request and response.
- A failed step rolls back the whole program and reports the correct step.
- A serialization conflict causes one whole-program retry at the client, never
  a partial server-side retry.
- Program close releases all plan references and session-retained memory.

Performance:

- Warmed protocol count is at most two messages per committed program
  transaction: one execute request and one response.
- Measured request/response counts exclude program setup but include failures
  and retries in separate counters.
- Server dispatch/decode is measured independently from relational execution.
- No steady-state per-step Java allocation is introduced by the program loop.

## 6. Step 2 — Make singleton steps point-native

### 6.1 Important current-state correction

The ordinary prepared SQL path already has a point implementation in
`SqlPointSelectExecution`: a safe equality predicate on a primary or unique
index can fetch directly and project without opening a general scan.

The program path is different. `TransactionProgramSteps.executeQuery` currently
does this for every query action:

```text
beginPreparedScan
  -> SqlScanCursor
  -> nextScan
  -> SqlScanRowResult
  -> TransactionValueReader
  -> TransactionProgramResult
```

Therefore the implementation task is not “invent point queries”. It is “route
program singleton actions through the existing point semantics and share the
projection/result machinery.”

### 6.2 Bound-invocation step classification

After restoring and binding a validated prepared plan for the current catalog
generation, derive a bounded physical step kind:

```text
COMMAND
POINT_PRIMARY
POINT_UNIQUE
SCAN_SINGLETON
ROW_SET
AGGREGATE
```

The classification is an execution hint, not a new semantic contract. Retain
the immutable classification or derive it at invocation, but do not retain a
session-bound `BoundSqlStatement` in a long-lived program handle. The program
pins the catalog generation recorded at preparation. If that generation is
stale, execution returns `PROGRAM_STALE` before `beginProgram`; the client
closes and prepares again. Re-preparation is setup work and is excluded from
the warmed execute interval.

`POINT_PRIMARY` and `POINT_UNIQUE` require, at minimum:

- equality access on the primary or unique key;
- no unresolved view/nested topology that changes cardinality or ownership;
- a projection that the point carrier can represent within its bound;
- locking semantics that can be performed by the existing current-row lock
  path;
- no condition that requires examining a second candidate row.

Test stale handles and catalog change between prepare and execute. A text
predicate without an appropriate unique access path remains a scan. A
point-looking SQL statement must not be forced into a point path merely because
the expected test data has one row.

### 6.3 Point execution contract

Introduce one internal point-step entry point shared by ordinary prepared SQL
and programs. Its responsibilities are:

1. Restore/bind the already prepared plan and parameter values.
2. Resolve the primary/unique access path.
3. Fetch one `HeapRowResult` under the correct snapshot/current-row rule.
4. Recheck predicates after a locking fetch where required.
5. Project directly into a reusable point result sink.
6. Enforce `EXACT_ONE` or `ZERO_OR_ONE` cardinality.
7. Release borrowed row/lock resources before the next program step unless the
   transaction contract deliberately retains them.

The point path must preserve:

- MVCC visibility and current-row semantics for `SELECT FOR UPDATE`;
- unique/foreign/check validation and before/after mutation images;
- one canonical row-lock identity and existing lock ordering;
- SQL NULL, decimal, text, and temporal semantics;
- statement rollback and transaction rollback behavior.

### 6.4 Result carrier shape

The carrier should be reusable by one execution lane and should expose:

```text
row presence: absent / present
column count
per-column: descriptor, null, fixed high/low or text slice
affected rows / generated key where applicable
borrowed source identity, if needed for final release
```

Fixed values should stay in primitive arrays. Text should use one bounded owned
lane with offsets and lengths. The carrier must not allocate a Java object per
column or row.

The existing `TransactionValueReader` idea is suitable for the semantic copy
operation, but its current package-private scope and source-specific adapters
should not force a second copy. Either move the shared value-view contract to
the correct ownership boundary or provide concrete sink methods that consume
the existing `SqlProjectedRow`/`HeapRowResult` synchronously.

### 6.5 Step 2 acceptance gates

- A primary-key `EXACT_ONE` program step increments a point-hit counter and
  increments no generic scan-open or `nextScan` counter.
- A unique-key `ZERO_OR_ONE` step returns absent without a scan when the access
  path proves uniqueness.
- A non-unique predicate and a query requiring a real row set continue through
  the scan/vector path and retain exact cardinality semantics.
- Point and scan paths produce byte-for-byte equivalent typed values for a
  matrix of fixed values, NULLs, text, wide decimal, and temporal types.
- Locking point steps show the same waits, retry status, lock ownership, and
  rollback results as the existing path.
- Warmed point execution has no per-step object allocation and no retained
  cursor state after completion.

## 7. Step 3 — Fuse typed publication and wire encoding

### 7.1 Current value crossings

The current program query path can cross several representations:

```text
storage HeapRowResult
  -> SQL projection/scalar scratch
  -> SqlScanRowResult
  -> TransactionProgramResult value arena
  -> protocol response ByteBuffer
  -> client TransactionProgramResult value arena
```

Some crossings are necessary. For example, a client result cannot retain a
server response buffer after the next request. The review target is to remove
unnecessary intermediate ownership changes and repeated per-cell traversal.

### 7.2 Shared typed-value layer

Define one internal typed-value contract used by:

- point result publication;
- command result capture;
- row-set/block result publication;
- transaction-program result capture;
- protocol program-result encoding.

The contract must support fixed values, wide decimals, NULL, and bounded UTF-8
text without boxing. It must distinguish:

- a borrowed read-only source view;
- an owned reusable carrier;
- an owned encoded destination.

Do not equate the storage physical tuple layout with the wire layout. The
storage tuple may be page-owned and versioned; the protocol value is externally
validated and lifetime-independent.

### 7.3 Server-side target pipeline

For a point program step, the preferred path is:

```text
indexed lookup
  -> predicate/SET/check evaluation
  -> shared typed result sink
  -> TransactionProgramResult owned arena
  -> response encoder
```

Avoid this additional intermediate path when no caller needs it:

```text
... -> SqlExecutionResult -> CommandResult -> TransactionProgramResult
```

For a real `ROW_SET`, retain a block/vector producer and write rows into the
bounded program result sink in batches. Do not force a block scan through the
singleton carrier merely to share code. The producer and singleton path should
share the typed-value write operations, not the physical iteration strategy.

### 7.4 Protocol encoding and result admission target

`EXECUTE_PROGRAM` needs a bounded logical response admission decision before
the transaction commits. `TransactionProgramExecutor` must not commit and
only then let `ProtocolProgramResultEncoder` discover that the result is too
large: that would create a committed-but-undeliverable transaction.

The current executor already calls `result.admitCommit()` before
`session.commitProgram(execution)`. Preserve that ordering; the implementation
work is to make the admission account for the complete logical response and to
prove it through the mutating-overflow and fitting-continuation tests below.

Reserve or incrementally account for encoded result bytes as each step
publishes. The account includes per-step metadata, value framing, continuation
metadata, and the maximum logical response size. If the result cannot fit,
return `RESOURCE_EXHAUSTED` before commit and roll back the whole program.
Expose the result-budget maximum, current usage, rejected bytes, and causing
step in the bounded run counters.

Physical continuation frames are a transport concern after logical admission.
A fitting logical result may use multiple frames, but fragmentation must not
allow a logical overflow to commit.

Choose one implementation: reserve a bounded logical-payload region in the
invocation-owned response workspace, encode values exactly once as they are
published, and patch the fixed header fields after the final size and commit
sequence are known. The same typed-value sink supplies embedded results and
the protocol builder; the protocol path does not later call a second
`bytes()`/`write()` traversal. Fixed numeric cells are emitted from primitive
values without temporary objects. If a cell cannot fit the reserved logical
region, publication returns `RESOURCE_EXHAUSTED` and the transaction is
aborted before commit.

Define the logical budget precisely:

```text
logical_result_bytes =
  program-result header
  + step metadata
  + row metadata
  + value headers
  + encoded value bytes
```

`logical_result_bytes` is compared with
`ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES`. It excludes the
outer frame header and every continuation-frame header. After admission and
commit, the transport computes:

```text
physical_wire_bytes =
  logical_result_bytes
  + one frame header per physical frame
  + continuation metadata per additional frame
```

The bounded response workspace must admit the maximum physical framing for an
admitted logical result before execution begins. Physical fragmentation may
split the admitted logical payload at frame boundaries, but it cannot enlarge
the logical budget or turn an overflow into a committed transaction.

The server response buffer must be released only after the socket write. The
client must copy from the response buffer into caller-owned result storage if
the caller needs values after the next exchange. A future borrowed wire-view
API is outside this route and must not be smuggled into JDBC semantics.

### 7.5 Copy and allocation accounting

Add explicit counters at the shared layer:

| Counter | Definition |
| --- | --- |
| `storage_to_carrier_bytes` | Bytes copied from a page-owned tuple into an owned result carrier |
| `carrier_to_program_result_bytes` | Bytes copied into the program result arena |
| `program_result_to_wire_bytes` | Bytes emitted into the response buffer |
| `wire_to_client_result_bytes` | Bytes copied/decoded into client-owned result storage |
| `text_length_passes` | Complete text traversals used only to determine payload size |
| `typed_cells_visited` | Semantic cells processed, separated by point and row-set path |
| `result_objects_allocated` | River-owned result objects allocated during measurement |

The counters must be updated in reusable primitive state or a bounded result
carrier. Do not format a diagnostic string per cell.

### 7.6 Step 3 acceptance gates

- Point command/query output is semantically identical before and after the
  fused path.
- For fixed-width point results, no intermediate row/result object is created
  between projection and program-result capture.
- Text results have at most one necessary lifetime copy on the server and no
  repeated length traversal in the steady-state encoder.
- Row-set results retain bounded backpressure and do not allocate per row.
- Response fragmentation/continuation has identical validation and ownership
  semantics for small and maximum-sized payloads.
- A mutating program whose logical result exceeds the response limit returns
  `RESOURCE_EXHAUSTED` before commit and leaves no mutation visible.
- A large-but-fitting result is fragmented, reassembled, and committed exactly
  once.
- Allocation and copied-byte counters demonstrate an improvement independent
  of the protocol request reduction.

## 8. Step 4 — Batch durability and expose contention

### 8.1 WAL group commit

The single-update trace shows why a program-only change cannot be the entire
50x route: the client observes a 5.756 ms commit, including a 4.11 ms WAL
force in that sample.

Eligible program commits enter the existing group path:

```text
program commit
  -> transaction outcome
  -> bounded WAL group reservation
  -> append cohort
  -> one durable force for the cohort
  -> publish committed state
  -> acknowledge each member
```

Required rules:

- write-bearing, point-only programs that satisfy the existing eligibility
  predicate use the bounded group coordinator;
- row-set, ordered, aggregate, range-protected, active-scan, lock-conflict,
  or otherwise initially ineligible programs use the direct path and are
  counted by their initial reason;
- a transaction admitted to grouped commit is never re-executed through the
  direct path: a pre-decision group preflight or admission failure aborts it
  once with the exact stage and status;
- read-only programs are reported separately and do not issue a WAL force;
- no program-specific direct force bypass and no new unbounded commit queue;
- no acknowledgement before the requested durability tier;
- bounded cohort size, wait time, and retained WAL bytes;
- a force failure fences or reports the same status as the existing commit
  path;
- group membership, coalescing wait, append bytes, force count, force latency,
  and cohort size are recorded.

Report `groupable_commits`, reason-tagged `direct_commits`, `read_only_commits`,
cohort size, queue wait, force count, force time, append time, and WAL failure
status separately. A one-terminal group of one is not evidence of amortised
force cost.

The canonical benchmark must keep the durability contract fixed. A relaxed or
asynchronous durability experiment may be useful for ceiling analysis, but it
is a separately labelled result and cannot be used to claim the River route is
complete.

### 8.2 Lock waits and escalation

The current lock implementation has explicit wait handles and queue states,
but the TPS acceptance output does not yet make lock cost visible. Add bounded
database or run-level counters for:

- lock acquisition attempts;
- immediate grants;
- queued waits;
- total and maximum wait nanoseconds;
- queue high-water and current queue depth;
- wakeups/grants after waiting;
- timeout, cancellation, deadlock, and retry outcomes;
- lock conversions/upgrades;
- held-lock high-water per transaction;
- lock escalation attempts and successful escalations.

If River has no lock escalation mechanism, report
`lock_escalations=unsupported_or_zero` explicitly rather than infer it from
absence of monitor events. Java `JavaMonitorEnter` and `ThreadPark` events are
not substitutes for logical row-lock counters.

The point fast path must preserve the existing ordering and canonical row-lock
identity. It may reduce lock duration by reducing CPU/copy work between lock
acquisition and publication, but it must not pre-lock speculative rows or
convert a point lock into a table lock to improve a benchmark.

### 8.3 Step 4 acceptance gates

- One-terminal runs show zero queued logical lock waits and no unexplained
  escalation events.
- Ten-terminal and deliberately hot-key runs report queue depth, wait time,
  deadlock/conflict/retry outcomes, and lock high-water values.
- Groupable, direct-fallback, and read-only commit counts plus cohort size
  explain commit latency. No durability acknowledgement moves earlier than
  the baseline contract.
- A lock-wait regression is not hidden by higher TPS; latency and boundedness
  gates still apply.

## 9. Implementation sequence and ownership

Work should be delivered as vertical slices with disjoint ownership. One lead
integrator owns the end-to-end contract; each specialist owns a disjoint set of
files and supplies tests/evidence to the integrator.

| Slice | Primary owner | Main files/boundaries | Review lens |
| --- | --- | --- | --- |
| Harness recovery and baseline | benchmark/operations | `tools/tps-test.sh`, profile/report scripts, preflight diagnosis | operations and measurement correctness |
| Program safety contract | transaction + boundary/protocol | result admission, isolation, outcomes, retry/fencing, cancellation | correctness adversary and protocol semantics |
| One simple program transaction | boundary/protocol + relational | existing program lifecycle, one prepared update | protocol/security and SQL semantics |
| Bound classification and point execution | relational/execution | shared point executor, `TransactionProgramSteps`, result sink | correctness adversary and allocation/performance |
| Result/encoding fusion | protocol/client + execution | typed carrier, program result writer, client decoder | ownership, wire correctness, allocation |
| TPC-C program families | relational/execution | program construction and action mapping in bench adapter | relational semantics |
| WAL/lock evidence | storage/transactions | group eligibility, commit counters, lock wait counters | recovery/concurrency and performance |

Before implementation begins, repair the current `RESOURCE_EXHAUSTED`
preflight failure and the runner's phase/status reporting in the shared
checkout. The program API is already wired; verify it through the real path
and add missing semantic coverage. Do not use a clean build to make that
diagnosis; use targeted compilation/tests and preserve other workers' changes.

### 9.1 Checkpoint 0 — restore a trustworthy measurement

Required command:

```sh
tools/tps-test.sh --warmup-seconds=5 --measured-seconds=20 \
  --terminals=1 --maximum-attempts=32 \
  --jfr=/private/tmp/river-tps-baseline.jfr
```

With `--jfr`, `tools/tps-test.sh` also passes a distinct JFR destination to
the managed server, defaulting to
`/private/tmp/river-tps-baseline.server.jfr`; `--server-jfr=PATH` overrides
that destination. The client recording is measured-phase scoped. The managed
server recording begins after server readiness and ends through the script's
graceful stop, so it covers startup/load/preflight as well as measurement and
must be filtered or separately marked when attributing measured hot paths.

Repeat at 1 and 10 terminals. A run that never enters measurement must emit
`preflight_failed`, not a valid-looking zero-TPS result. A measured run must
complete with non-zero commits, zero unexplained errors, an artifact, a client
JFR covering the measured phase, and a managed-server JFR covering that
interval (with its broader startup/load scope marked).
The folded-stack report is generated by:

```sh
tools/jfr-flamegraph.sh \
  --jfr=/private/tmp/river-tps-baseline.jfr \
  --folded=/private/tmp/river-tps-baseline.folded
```

Require at least 100 execution samples before using a CPU stack ranking as a
decision gate. If the workload is mostly I/O-waiting, use the exact per-step
CPU/wall counters, socket latency, server JFR, allocation counters, and
protocol counts instead of over-interpreting a sparse flamegraph.

The runner's phase/status contract is part of this checkpoint. It must record
`load`, `preflight`, `warmup`, `measured`, `drain`, and `checkpoint` separately,
including the exact `StatusCode`. If measurement never starts, the terminal
summary is `preflight_failed` with a non-zero process status; it must not emit
`tps=0.000` as though zero committed work had been measured. Once measurement
starts, report started, completed, failed, retried, rolled-back, and
still-in-flight attempts with an explicit drain policy.

The human-readable summary reports request/response averages to one decimal
place, separately for logical exchanges and physical frames, for example:

```text
logical_exchanges_per_attempt=1.0
physical_request_frames_per_attempt=1.0
physical_response_frames_per_attempt=1.0
protocol_requests_per_commit=1.0
```

The artifact also retains unrounded counters, raw histograms, p50/p95/p99/
p99.9/max latency, request and response bytes, client/server CPU and
allocation evidence, copy/text-pass counters, result-budget counters,
group/direct/read-only commit counts, and lock wait/escalation counters.

### 9.2 Checkpoint 1 — program safety contract

Implement and test the semantic gates before changing the physical execution
path:

- candidate and canonical baseline use the same explicitly reported isolation;
- a mutating program whose result exceeds the logical response limit returns
  `RESOURCE_EXHAUSTED` before commit and leaves no mutation visible;
- a large-but-fitting result can use continuation frames and commits exactly
  once after reassembly;
- expected invalid-item business rollback is distinct from generic failure and
  is included in family accounting;
- only server-confirmed serialization/deadlock outcomes are retried;
- a lost response after execution fences the session or returns an explicit
  indeterminate outcome and is never blindly replayed;
- timeout/connection-close cancellation has a tested, documented guarantee;
- stale handles and catalog-generation changes fail or rebind atomically.

This checkpoint is complete only when result admission occurs before the
commit decision and the outcome is observable in the protocol and benchmark
manifest.

### 9.3 Checkpoint 2 — simple update program

Use a one-update transaction with one prepared command and no result row. Prove
the complete path before mapping TPC-C:

- setup: prepare statement and program;
- measured: one execute-program request/response and one durable commit;
- failure: conflict/rollback and stale handle;
- teardown: close program, session, and transport.

Compare against the existing three-request update path. Report request count,
wall time, client/server CPU, force latency, bytes, allocations, and copies.

### 9.4 Checkpoint 3 — one point query plus mutation

Use a transaction containing one `EXACT_ONE` primary-key lookup and one update
that consumes the lookup result. Prove:

- dataflow capture without `SqlScanRowResult`;
- exact cardinality and missing-row failure;
- locking and current-row correctness;
- direct result capture and program response encoding.

This is the first checkpoint at which the row-at-a-time suggestion is measured
independently of protocol savings.

### 9.5 Checkpoint 4 — one real row set

Use a bounded multi-row step, including empty, one-row, and many-row results.
Keep the block/vector executor and measure row/cell copies, response size,
continuation, and client decode. This prevents the point fast path from
regressing genuine scans.

### 9.6 Checkpoint 5 — TPC-C family programs

Map Payment and New Order first, then Delivery, Order Status, and Stock Level.
Run the existing no-wait-stress schedule and retain the standard family mix.
Do not change the workload to hide a transaction family that is slower after
program execution.

### 9.7 Checkpoint 6 — concurrency and durability

Repeat at 1, 10, and increasing terminal counts with:

- normal key distribution;
- deliberately hot warehouse/district/customer keys;
- local durable WAL;
- any separately labelled quorum/replicated durability tier.

The output must explain TPS, p99 latency, protocol reduction, group force,
lock waits, retries, and bounded queue high-water together.

### 9.8 Normative Alpha3 capacity gate

The qualifying capacity gate is defined normatively by
[`alpha3-tpcc-capacity.md`](alpha3-tpcc-capacity.md), not by this route's
short diagnostic commands. At each declared scale point, use the Alpha3
manifest and no-wait 45/43/4/4/4 family mix, at least five warmups, ten
measured samples, and at least 100,000 completed transactions in every
sample. Require zero unexpected failures, all invariant and recovery checks,
and a 95% confidence interval lower bound of at least 1,000 committed TPS.

The route's one- and ten-terminal short runs establish mechanism and
regression evidence only. They cannot be promoted as the Alpha3 capacity
result. For a River/MariaDB comparison, use the identical Alpha3 scale, seed,
scheduling, isolation, acknowledged durability, workload mix, and host; report
the additional Alpha3 relative-performance gate separately.

## 10. Test and evidence matrix

### 10.1 Functional and semantic tests

| Area | Required cases |
| --- | --- |
| Program lifecycle | prepare, execute, close, stale handle, catalog invalidation, session close |
| Commands | insert/update/delete, affected rows, generated key, NULL/text/decimal parameters |
| `EXACT_ONE` | present, absent/cardinality violation, locking read, projection/dataflow |
| `ZERO_OR_ONE` | present, absent branch, locking read, predicate recheck |
| `ROW_SET` | empty, one, many, continuation/fragmentation, maximum bounded row/text shape |
| Result admission | oversized mutating result rolls back before commit; fitting continuation commits exactly once |
| Transaction outcome | commit, business rollback, statement failure, serialization retry, cancellation before commit, indeterminate/fenced failure |
| Values | fixed numeric, wide decimal, UTF-8 including surrogate pairs, temporal, NULL |
| Ownership | response reuse, result reuse, close-after-failure, no borrowed page after release |
| Durability | WAL append/force failure, restart/recovery, commit acknowledgement ordering |
| Concurrency | point conflict, queued wait, timeout, deadlock, cancellation, lock release, group/direct/read-only commit path |

### 10.2 Performance tests

| Layer | Test | Decision |
| --- | --- | --- |
| L1 | Typed carrier copy/encode, point publication, text length/encode | Mechanism throughput, allocations, copied bytes |
| L2 | Program request codec, server dispatch, result encoding, WAL group force | Component attribution |
| L3 | Embedded and loopback simple update, point lookup/update, row set | Isolate engine and protocol effects |
| L4 | TPC-C one/ten terminal and contention scale | User-visible TPS and latency |
| L5 | Checkpoint/vacuum/backup/restart interference | Ensure optimisation does not move cost into maintenance or recovery |

Each candidate is compared with the same JDK, heap, database image, seed,
durability tier, warm-up, and measured duration. Five-sample runs are
diagnostic only. A promotion claim uses the normative Alpha3 gate below:
ten measured samples per system/configuration, at least 100,000 completed
transactions per sample, and a 95% confidence interval whose lower bound is at
least 1,000 committed TPS. Instrumented JFR runs are for attribution;
canonical TPS runs must quantify profiler overhead separately.

## 11. Review questions and rejection conditions

The review amendments make the following questions implementation gates:

1. Does the already wired program protocol pass end-to-end lifecycle,
   malformed-input, stale-handle, resource, and exact-outcome tests?
2. Which existing point executor is extracted and shared, and where is the
   proof that no duplicate SQL semantics were introduced?
3. Which exact copy is necessary for each value lifetime, and which copies are
   removed? Are bytes and allocations counted at the boundary?
4. How does the encoder know response length without traversing text twice?
5. Which programs are group-eligible, which are initially direct, and which are
   read-only? Does each preserve the existing durable acknowledgement point?
6. What is the bounded behaviour when the program result, response buffer, WAL
   cohort, or lock queue is full?
7. How are catalog-generation changes and stale program handles handled?
8. How are lock waits distinguished from CPU, socket wait, WAL force, and
   client-side result decoding?
9. Are protocol, mechanism, and capacity reported with separate denominators,
   with Alpha3's 1,000 committed TPS as the capacity gate and 50x retained as
   an aspirational ratio until paired confidence evidence exists?

Reject the implementation if it:

- reduces request count by weakening commit/durability or retry semantics;
- adds a second executor or a TPC-C-specific engine branch;
- allocates per step/row/cell in the warmed path without a reviewed exception;
- aliases mutable storage or response buffers beyond their owner lifetime;
- forces row sets through a singleton path or singleton queries through a scan;
- hides lock waits, WAL force, errors, or retries from the report;
- claims a 50x result from a sparse flamegraph or an instrumented run alone.

## 12. Expected leverage

The expected order of impact is:

| Priority | Change | Why |
| ---: | --- | --- |
| 1 | One `EXECUTE_PROGRAM` per transaction | Removes approximately 28.8 ordered request cycles per average transaction and approximately 48.2 for New Order |
| 2 | Point-native program steps | Removes generic scan cursor, row wrapper, and per-cell publication work from the many singleton TPC-C operations |
| 3 | Shared carrier and one-pass result encoding | Removes intermediate result copies and repeated text traversal; becomes visible after request collapse |
| 4 | WAL cohorting and lock evidence | Amortises force cost under concurrency and identifies whether contention, rather than CPU, limits scale |

The first change is the only plausible single order-of-magnitude lever. A
credible 50x result will require the changes to compose, and it must be
verified with the actual `tools/tps-test.sh` workload after the current
preflight `RESOURCE_EXHAUSTED` failure is fixed and the paired benchmark
contract is satisfied.
