# tic-b1b7 declared concurrency matrix

Test-only delivery from 590c4bfd. Existing scheduler gauges establish pending
requests; synchronous statement completion establishes acquired SQL locks.
No production hooks, timing guesses, copied grant predicates or retry loops.

| Case | Real SQL path and isolation | Controlled schedule | Expected proof |
| --- | --- | --- | --- |
| Two-owner cycle | Primary point UPDATE, RR/RR, RR/S, S/S | A updates row1; B updates row2; queue A on row2; B requests row1 | Existing youngest victim B, A progresses; rollback/reuse and exact correlated cycle |
| Three-owner cycle | Secondary predicate UPDATE, S/S/S | A/B/C own rows1/2/3; queue A on2 then B on3; C requests1 | C victim; B then A progress and commit; three scheduler edges |
| Conversion | Primary point SELECT then UPDATE, S/S | A/B retain shared read; queue A conversion; B converts | B victim, conversion dependency and A progress |
| Queue-order cycle | Primary point reads/updates, S/S/S | A shared row1; C exclusive row2; B queues exclusive row1; A queues exclusive row2; C requests shared row1 | Cycle includes a scheduler queue-order dependency; youngest C victim |
| Acyclic control | Same primary point UPDATE paths and isolation pairs | A owns row1; queue B; A commits | B completes; zero victim selections |
| Repeatable read | Primary and secondary point reads, RR reader with RR/S writer | Reader establishes snapshot; writer updates and commits; reader reads again | Existing repeatable-read values, visible new value in next transaction; no stronger RR promise |
| Phantom protection | Primary and secondary range scans, S reader with RR/S writer | Drain reader range; outside insert commits; inside insert queues; reader repeats and commits | Same protected range, outside progress, inside insert completes after release; scheduler scope captured |
| Integration regression | Existing TpccPayment and TpccRiverNewOrder program bodies; mixed RR/S and S/S; two and three clients | Pause real Payment after warehouse UPDATE; start New Order and observe its wait; add queued Payment for three clients; release holder | Expected serialized progress, real SQL/program paths, actual isolation, no false cycle/retry, final invariants and cleanup |

Cases repeat with fixed inputs and explicit order. Expected victim checks are
subject to confirmation against the current owner, not a new selection policy.
Diagnostic admission uses existing bounded capture; all terminal owners and
workers must drain on success or failure. Retain full scheduler output alongside
assertions. A missing necessary seam or real database defect stops its case for
the separately scoped decision required by the ticket. This matrix does not run
or alter the P0 performance campaign.

## Proof and limits

`SqlGeneralConcurrencyTest` and `SqlConcurrencyIsolationTest` execute 38 database
cases: 12 selected cycles and 26 acyclic/isolation cases. Every case repeats
with the declared ordering. The 13 JUnit invocations include the finite nested
isolation/access-path cases. `TpccConcurrencyReproducerTest` adds eight
server/JDBC cases in two repeated JUnit invocations: Payment RR or Serializable,
New Order Serializable, each with two or three clients.

Primary point cycles use KEY dependencies for RR/RR and RR/S, and TUPLE_KEY
for S/S. The secondary predicate UPDATE cycle records TUPLE_RANGE dependencies
in secondary namespace 2. EXPLAIN identifies secondary index ordinal 1 for the
point/range readers; the executed UPDATE cycle's scheduler scope and namespace
independently establish its secondary path. The insert blocked by a protected
range reports the requested TUPLE_KEY scope with a SHARED owner. Range stability,
outside-insert progress and inside-insert release prove the scan's protection.

Each cycle checks its exact directed owner map, attempt/SQL step correlation,
requested/held modes, queue relationship, denied grant precondition, namespace,
and current youngest victim. Read-only test reflection reaches the existing
transaction-manager snapshot for producer resource digests: distinct resources
for different-row cycles, identical KEY resources for the two row1 FIFO edges.
Conversion's KEY and TUPLE_KEY digests differ because scope is part of identity,
even though both requests target the same SQL row. No encoding or grant predicate
is reimplemented. Each cycle has one victim outcome, one queued cancellation,
one explicit successful ROLLBACK and zero retries; released-holding accounting,
survivor commits, rolled-back values and immediate session reuse are checked.
All owners are closed, including after assertions fail; final transaction,
lock, waiter and retained-snapshot gauges are zero.

The engine tests inspect the actual transaction's isolation enum after real SQL
BEGIN through one read-only helper. Integration isolation evidence is composed:
JDBC set/get verifies the configured level, existing JDBC/server propagation and
program-isolation behavior remain unchanged, engine cases verify effective
levels, and New Order's observed SHARED warehouse wait has the expected KEY
(mixed) or TUPLE_RANGE (common Serializable) classification. The test does not
claim that the cached JDBC getter directly observes the server transaction enum.
The test proxy pauses only after the existing Payment warehouse UPDATE returns;
it forwards every JDBC operation and copies no transaction body. The real
TpccRiverNewOrder program and optional second Payment queue before release.
Committed return values, business invariants, consumed block counts, no retries,
no false victims/cancellations/timeouts and complete cleanup are asserted.

## Reproduction

From the delivered revision, using JDK 25:

```sh
./gradlew --no-daemon :river-engine:test \
  --tests io.riverdb.engine.sql.SqlGeneralConcurrencyTest \
  --tests io.riverdb.engine.sql.SqlConcurrencyIsolationTest \
  :river-bench:test \
  --tests io.riverdb.bench.tpcc.TpccConcurrencyReproducerTest
```

Affected-module validation:

```sh
./gradlew --no-daemon :river-engine:test :river-bench:test \
  :river-server-app:test verifySourcePolicy verifyModuleGraph
```

Delivery used `JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home`,
`GRADLE_USER_HOME=/private/tmp/river-b1b7-gradle`, and
`--project-cache-dir /private/tmp/river-b1b7-cache` in the isolated
`/private/tmp/river-b1b7` checkout on branch `ticket/tic-b1b7-general-concurrency`.
The full logs and JUnit case/SQL/edge/resource/capture output are retained at
`/Users/blater/src/river-performance-evidence/20260914-b1b7/`.
Independent concurrency and relational review by `execution_admission_review`
approved the final test-only source and causal assertions; affected-suite results
are recorded in the ticket's delivery note. No database defect was exposed by
these cases. This evidence does not run or certify the P0 promotion campaign.
