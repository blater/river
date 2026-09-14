---
id: tic-gwindor
status: closed
type: story
priority: 1
assignee: blater
parent: tic-primula
external-ref: /Users/blater/src/ingres/river-harness@4ff2a71d673e736561ad3ed3b26f31b3121ebff8
delivery: evidence
base-commit: 3cfe00e7a1d5817a70308d106c4f27d2eba71a14
branch: ticket/tic-gwindor-catalogue-consumer
evidence:
    - /Users/blater/src/ingres/river-harness/docs/tickets/prepared-catalogue.md
tags:
    - performance
deps:
    - tic-edoras
created: 2026-09-13T11:46:48.671624Z
---
# Retain prepared handles across repeated client executions

### Outcome and admission

Reduce measured client prepare/release churn for repeated statements on the same live connection, only where the existing client does not already retain them.

This is a downstream mechanism candidate, not an instruction to implement before
its dependencies deliver affirmative evidence. At handover, the lead records the
exact affected path, measured cost, resource/lifetime contract, and expected
mechanism change in this ticket. If the cost is absent or the mechanism already
exists, record a no-change disposition and revise dependent promotion scope;
never invent work or mark undelivered code as shipped.

### Owner and bounded scope

The selected consumer is the independently owned harness's common SQL binding.
Use existing database/sql statement ownership for both River and MariaDB;
do not add a Java or Go-driver cache. Server shared-plan ownership from tic-7a32
and one-way release from tic-6f28 remain unchanged.

Use a named repeated execution consumer from tic-edoras. Keep parameter values/results execution-local; preserve connection and transaction ownership, schema invalidation and server restart/reconnect semantics. Retained handles are bounded by existing resource admission and released exactly once. No new server plan cache; no benchmark semantics in the client.

### Current-source admission: external Go consumer

Independent read-only review `protocol_reuse_audit`, 2026-09-13, found the actual
missing reuse in the external harness. In
`river-harness/internal/binding/tpcc/sqlfull/transactions.go`, OpenFullWorker pins
a sql.Conn and prepares the catalogue on that connection; every statement use
then calls tx.StmtContext on the retained connection-bound statement. Installed
Go 1.27.1 database/sql/sql.go sets cg on Conn-prepared statements, and
Tx.StmtContext calls ctxDriverPrepare again when cg is non-null. The River
adapter's connection.PrepareContext sends a fresh PREPARE on every such call.
Transaction completion closes those temporary statements; the worker's original
handles remain retained but are not the handles executing the transaction.

The source-derived extra-prepare count is 6 + 4 times the valid line count for a
successful New-Order attempt, and seven for Payment by customer ID. These are
predictions to verify with a counting driver/protocol test, not measured counts.
The common SQL binding affects River and MariaDB; retain its identical SQL,
isolation, transaction context/cancellation and outcome classification.
Do not bypass transaction ownership by executing Conn statements outside Tx.

The retained tic-da4e Java-server profile was driven by this Go harness. It
records 29/1,185 inclusive selected request/commit stacks and 53,711,608 weighted
inclusive allocation bytes at SessionEndpoint.prepare during the measured
recording. The reviewer confirms a real consumer and measured preparation cost;
no additional admission-only profile is needed. Implementation admission remains
conditional on resolving the lifecycle, cleanup and cancellation requirements
below. Exact redundant counts, removable cost and useful benefit still require
counting tests and matched runs.

Java TPS already retains JDBC and program handles in terminal-owned resources;
no Java cache or server plan-cache change is admitted. Before claiming code,
tic-edoras must record the concrete external repository ownership and chosen
statement-lifetime contract, including schema change, live result/handle release,
failed construction, cancellation and lost transport. This source admission does
not remove that dependency, deliver code or settle pipelining admission.

### Preferred implementation contract and source ownership

Independent protocol review prefers a binding-owned catalogue of
`DB.PrepareContext` statements over a driver cache of SQL-to-handle aliases.
Initialize the catalogue after successful `LoadFull` (which verifies the load)
and before either warmup or measured workers pin connections. Keep the same
worker-owned `sql.Conn`, `BeginTx` and `Tx.StmtContext` calls. For DB-owned parents,
Go's existing statement directory reuses or prepares on the transaction's exact
physical connection; it does not need another pool slot. Transaction wrappers
retain their normal context and closed-transaction behavior. Parent statements
remain live across both worker phases and close after every worker has returned.

This replaces per-worker connection-bound catalogue preparation completely in
the common binding. It changes no workload SQL, mix, isolation or retry rule,
and requires no River protocol version, server-plan cache or Java API change.
An independent public driver PrepareContext still performs actual server
preparation. A generic cache hit would otherwise skip preparation-time
authorization, schema validation, resource admission and the active-query
conflict check; such an alias cache is not admitted.

External repository ownership is `/Users/blater/src/ingres/river-harness`:

- `internal/binding/tpcc/sqlfull/binding.go` and `transactions.go`: catalogue
  ownership, partial-construction cleanup, borrowed worker references and release.
- `internal/suite/tpcc/full.go`: explicit preparation/release methods on the
  existing FullBinding lifecycle, migrating its implementations and test doubles.
- `cmd/river-harness/full_phases.go`: prepare after load and complete validation
  after both worker sets return; retain partial catalogue ownership for cleanup.
- `cmd/river-harness/managed_river_run.go` and `full_run.go`: join earlier
  catalogue-cleanup errors with database close/drop errors instead of overwriting
  `phases.finalizeErr`. Both target paths must preserve the outcome. These
  finalizers own catalogue release followed immediately by physical DB close,
  after validation and any database-drop queries have completed.
- `internal/dbms/river/driver_test.go`, protocol fixtures and common binding
  tests: prove ownership and release behavior through the real database/sql path.
- If the terminal-error remedy below is accepted, `internal/dbms/river/protocol.go`
  and its existing close caller in `driver.go` own that behavior; no parallel
  cleanup/error API is admitted.

The parent catalogue has the existing finite statement inventory; underlying
handles follow live physical pool connections and server resource admission.
Do not add a separate idle cache or retain handles after catalogue/pool closure.
Pool replacement must prepare on the replacement connection, never reuse an ID
from another session. Lazy first-use preparation on a new physical connection
must be counted in its actual phase; do not promise zero measured prepares after
a short warmup. Preserve normal execution-time authorization and schema rebinding
in SqlSessionStatementPreparation, including private/rolled-back DDL tests.

### Database-free ownership evidence, 2026-09-13

An isolated Go 1.27.1 counting-driver fixture executes the actual database/sql
ownership path. Six executions across two transactions produce seven prepares
and seven final releases with a Conn-owned parent, versus one prepare and one
final release with a DB-owned parent. Two fully pinned pool connections across
two worker phases produce two prepares, four executions and two final releases;
no spare pool slot is required. Closed transaction children reject execution.

The first fixture incorrectly expected DB-parent Close to immediately release
a handle on a still-pinned connection. Go's noteUnusedDriverStatement queues
that release until connection return. The corrected test explicitly asserts the
deferral, then verifies exact release after return. The final run passes both
top-level tests (including both ownership variants) in 0.514 seconds under the
stop guard, with no watchdog intervention. No database, network or TPS workload
was run; these counts are mechanism evidence, not measured workload improvement.

The fixture and logs are retained at
`/Users/blater/src/river-performance-evidence/20260913-edoras/statement-ownership/`.
Remaining admission includes active Rows/parent closure, partial preparation,
context cancellation, schema changes and uncertain transport outcomes.

Independent review confirms a cleanup blocker: database/sql discards errors from
driver statement release, and DB-parent finalClose returns nil. The current
River protocol close also returns nil once the transport is marked closed, so
later DB.Close need not expose a failed one-way release. Deferred onPut releases
run after the pool's IsValid check. Neither nil sql.Stmt.Close nor IsValid proves
successful release or error reporting. Establish and test terminal cleanup error
propagation through finalization before admitting the catalogue lifecycle.

The reviewer's proposed narrow remedy is for the existing transport owner to
retain its first terminal transport failure and return it from its existing close
outcome, even when already logically closed. After all worker/Rows owners return,
complete validation and any drop queries, then release the catalogue and close
the physical DB without intervening pool activity. Join that close outcome into
the existing finalization result; never translate it to ErrBadConn or retry.
This is conditional: if a failed connection can be discarded before the final
stage, this sequencing alone is insufficient. Test one-way release write failure
under a real database/sql parent, both idle and deferred pinned release; prove
failure reaches the final run outcome, remaining owners release once and no retry
occurs. A deferred-release path may be excluded only by proven lifecycle ordering.
Keep partial preparation failures in the same owned finalization contract.

### Isolated release-failure proof, 2026-09-13

The cleanup hypothesis is now reproduced with actual database/sql and unchanged
copies of the harness driver/protocol from aba7c43f. An in-memory net.Conn injects
CLOSE_PREPARED write failure. Both immediate idle release and deferred pinned
release fence the transport, yet the original Stmt.Close and final DB.Close return
nil. The baseline also loses the original failure across repeated protocol close;
a CLOSE_QUERY transport failure can be replaced by ErrBadConn. Healthy cleanup
and the deliberate pool-activity counterexample pass in the same baseline.

A copied-code-only candidate retains the first terminal transport failure,
returns it through the existing close result, joins a physical-close failure
without losing the original cause, and avoids another physical close or request
after a terminal failure. All six top-level tests and four subtests pass: idle
and deferred release failure, healthy release/session close, multiple logical
statement owners, CLOSE_SESSION/CLOSE_QUERY failure, physical-close failure and
the pool-activity counterexample. Error identity and transport classification
survive; no ErrBadConn translation, reconnect or release replay is introduced.
Independent protocol_reuse_audit approves only this isolated error-retention
contract. The actual harness repository is unchanged.

The passing counterexample proves the remaining limitation: acquiring and
returning a pool connection after catalogue release can discard its close error
before final DB.Close. Production finalization must therefore prove no intervening
pool activity; the candidate does not fix arbitrary cleanup sequencing. Partial
construction, all worker/Rows ownership, cancellation deadlines, actual TLS and
workload validation remain requirements before full admission and delivery.

Evidence, baseline source, candidate patch and commands are retained under
`/Users/blater/src/river-performance-evidence/20260913-edoras/release-failure/`.
These are database-free fault tests, not observed River hangs. No watchdog stop
occurred. An initial malformed synthetic PREPARE response was corrected before
the negative-control run; its log is retained separately.

The harness currently has no Git remote. Its publication destination and delivery
ownership must be resolved before cross-consumer promotion; local source tests
are still useful evidence. This handover does not close tic-edoras or tic-gwindor,
remove the existing dependency, or authorize incomplete cancellation behavior.

### Acceptance and adversarial tests

Test changing parameter/null types, early client close, release ordering, invalidation/reprepare after DDL, handle exhaustion/eviction, connection loss and restart, independent handles sharing a plan, and cancellation. Compare Java/Go results and resource release. Show lower prepares/releases per attempt and reduced end-to-end cost, with no unbounded server retention.

Apply the shared execution/promotion contract in
[the handover](../plans/performance-three-epics-handover.md). The story owns one
coherent merge/rollback boundary with all River-owned callers migrated and the
superseded path removed. Profiled runs prove the mechanism; separate matched
uninstrumented runs establish performance. No per-row allocation, unbounded
retention, extra execution path, weakened isolation/durability, or benchmark-family
policy may be introduced. A negative result is a documented rejection, not a
performance delivery. Independent review is required before promotion.

### Final admission boundary, 2026-09-14

Use the single Database-owned cleanup outcome and physical-lifetime join reviewed
in tic-edoras, replacing the copied per-protocol error-latch proposal. Add
internal/dbms/river/database.go to its existing adapter ownership alongside
driver.go/protocol.go; this is the same exact-cleanup contract, with no added
transport, protocol operation or retry behavior. The test-first admission probes
cover cancellation-driven discarded connections as well as idle/pinned release.
External delivery is local only at the user's direction, on
`ticket/prepared-catalogue` in river-harness. No remote is requested or configured.

### Local implementation and review, 2026-09-14

External feature ticket/prepared-catalogue contains production6c5f655 and final
fault-test/documentationebdab46. The user selected local delivery; no harness
remote is configured or required. Only the admitted common binding lifecycle and
River database cleanup ownership change. One binding-owned DB.PrepareContext
catalogue replaces every worker-owned Conn.PrepareContext catalogue; existing
pinned workers and Tx.StmtContext remain. One Database cleanup latch and physical
lifetime join capture discarded errors. No driver cache, duplicate statement
inventory, transport rewrite, new timeout or server change is introduced.

Actual database/sql tests prove two fully pinned workers across both phases:
2N physical prepares and final releases for4N executions of the N-statement
catalogue. Partial failure/cancellation releases only acquired parents and admits
no worker. Active rows finish before catalogue cleanup. Idle/deferred release
failure, pruning before asynchronous physical close, ordinary cancellation,
opening cancellation concurrent with Close, remaining parents after failure and
first release cause retention all pass under the race detector. A fully written
COMMIT followed by EOF executes exactly once and remains a transport error,
neither retryable nor definite rollback nor ErrBadConn; final physical close is
exactly once. This establishes unknown delivery without replay, not the server's
durable outcome. Earlier classification tests preserve the final unknown outcome.

Baseline fixtures reproduce lost release failure and premature database close.
Full Go tests, race tests and vet pass; the final affected adapter race test also
passes after the COMMIT fixture. Independent execution_admission_review approves
source ownership, fault evidence and scope. Slopmark remains unchanged for
protocol26.8542, binding21.2396, driver11.6671 and database5.68752;
transactions falls7.1648 to6.60964. Tests and evidence do not add production
profiling or another outcome owner. Workload and final promotion evidence follow
in tic-rian and the shared checkpoint ledger.

### Accepted external delivery

Local harness integration is 4ff2a71d673e736561ad3ed3b26f31b3121ebff8 on main,
with annotated perf-checkpoint-20260914-prepared-catalogue. Feature commits are
6c5f655 (production) and ebdab46 (final fault test and local delivery note).
The installed harness executable now uses those tested production bytes; the
baseline executable is retained in the evidence archive. River records this
independently owned code delivery as evidence, following the existing external
harness convention; it does not pretend the external commit belongs to River.
The external reference and evidence identify the actual code repository.

Independent execution_admission_review grants final promotion after all required
checks and measured mechanism/workload results recorded in tic-rian. Harness
publication remains local only at the user's instruction. No remote destination
or extra publication work is introduced.
