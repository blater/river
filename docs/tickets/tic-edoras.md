---
id: tic-edoras
status: closed
type: investigation
priority: 1
assignee: blater
parent: tic-primula
delivery: evidence
evidence:
    - docs/tickets/tic-da4e.md
tags:
    - performance
links:
    - tic-da4e
    - tic-morgoth
created: 2026-09-13T11:46:48.664078Z
---
# Admit transport optimizations from current exchange and ownership evidence

### Outcome

Select a concrete remaining protocol mechanism from current-master evidence,
using tic-da4e. Preserve the existing ordered authenticated transport, transaction
program executor and public SQL contract; do not assume Payment must become one
request or that River still has the removed prepared-close acknowledgement.

### Required decisions

- Attribute exchanges, flushes, bytes, waits and lock residence by workload family
  and by attempt/commit. Distinguish prepared server-plan reuse (already delivered)
  from client handle churn. Identify independent requests versus those needing a
  prior value, status or transaction result. API capability alone is not a consumer.
- Decide separately whether `tic-gwindor` and `tic-morgoth` have useful
  real consumers and material removable cost. For pipelining name the first real
  caller and exact request sequence it can submit without dependent values; if
  none exists, reject the candidate. Any new generic program operation needs its
  own independently reviewed contract; do not smuggle workload semantics into River.
- Specify ordered response association, first-error handling and drainage,
  transaction/autocommit/savepoint boundaries, cancellation, transport loss,
  indeterminate commit and retry ownership. No automatic replay of unknown commits.
- Specify configured byte/request admission, pending-response and streaming-result
  pressure, fairness, buffer lifetime/erasure and the barrier after one-way release.
  State whether framing/version must change; migrate Java and external Go consumers
  together if it does, with no legacy dual path.
- Record the exact River/harness repository file ownership and protocol fixtures.
  External harness workload SQL/mix/isolation/retry semantics remain identical;
  its Go adapter is a separate repository delivery. Missing publication ownership
  is an explicit blocker for the affected cross-repo story, not permission to
  embed harness policy in River.

Independent boundary/security and relational reviewers approve the selected
contract plus partial-failure/streaming matrix before code. A rejection/defer
with evidence is a completed investigation. Supersedes the unconditional Payment
mapping/one-request pilot in tic-00e1/tic-af0a; those are not delivered mechanisms.

### Source frontier, 2026-09-13

Independent review identifies redundant Go transaction statement preparation as
the first concrete reuse consumer; tic-gwindor records the exact stdlib/adapter
path and matching Java-server profile. Java TPS handle reuse already exists.
Complete the external lifetime/release/cancellation contract before coding;
do not add a second Java cache to reproduce delivered behavior.

The presently identified batch/pipelining consumer is Java loading through
TpccBatch and RiverJdbcBatchExecutor. That is outside measured transaction TPS,
so it does not admit tic-morgoth as a transaction throughput optimization.
Payment retains status, returned-value and streamed-result dependencies; the
partial-failure/drainage contract remains outstanding. The external River Go
adapter's existing 30-second request timeout is unchanged; the withdrawn
15-second policy creates no prerequisite or transport work here.
No new workload was run for this source review. Ubuntu validation is deferred
to the final sweep, as directed by the user.

### Consumer contract refinement, 2026-09-13

tic-gwindor now specifies the preferred common-harness mechanism: prepare a
DB-owned statement catalogue before workers pin connections, keep Tx.StmtContext
and normal transaction ownership, and release the catalogue after both worker
phases return. A database-free Go counting-driver fixture confirms the prepare
reduction and reuse with every pool slot occupied. A generic driver alias cache
is not admitted because a cache hit changes preparation-time validation and
active-query behavior. No protocol framing/version change is needed for the
preferred mechanism.

The independent reviewer identified the required external lifecycle methods and
both target finalizers that currently overwrite earlier cleanup errors. The
fixture also establishes that DB-parent release can wait for pinned-connection
return. Error propagation from one-way release, active Rows lifetime, partial
construction, schema change and cancellation remain admission requirements;
nil sql.Stmt.Close alone cannot prove remote cleanup. Exact file ownership and
required implementation behavior are recorded in tic-gwindor. The harness has
no configured Git remote, so its publication remains unresolved.

This advances the contract investigation with executable ownership evidence;
it does not complete broader exchange/wait attribution, admit pipelining, waive
tic-da4e, or constitute production delivery.

The next isolated fixture reproduced swallowed release failures through the
actual Go driver/database/sql path and validated first-terminal-error retention
in a copied protocol owner. Independent review approves that narrow contract;
tic-gwindor records the baseline failures, six passing candidate tests and exact
artifacts. A passing counterexample also proves error loss can recur with pool
activity between catalogue release and DB close. Finalizer ordering, partial
construction and cancellation remain implementation-admission requirements;
no harness production code has changed.

### Reviewed bounded consumer decision, 2026-09-14

Independent protocol review `execution_admission_review` admits gwindor's
DB-owned catalogue consumer and rejects morgoth without implementation. The
retained prepare profile and database/sql counting fixtures establish removable
preparation, while no measured transaction consumer admits ordered pipelining.
The broader wait-attribution investigation remains tic-da4e, now linked rather
than a completion prerequisite for this concrete decision.

The selected ownership contract replaces the earlier per-protocol terminal-error
draft. River's existing Database lifetime owns one retained first cleanup error
and a join of its admitted physical connection lifetimes. Existing Database.Close
fences new admission, closes sql.DB, joins physical cleanup, then returns the
retained outcome. No second public error API, error history, retry path, queue,
executor or deadline is admitted. A connection is registered before dial and
completed exactly once after failed open or final physical cleanup. Statement
release and connection cleanup record otherwise-discarded errors at this owner;
ordinary operation outcomes keep their existing classification.

This join is required by actual Go cancellation ownership: Tx.awaitDone can
discard a connection asynchronously, Conn.Close may already return ErrConnDone,
and sql.DB.Close need not wait for that in-flight discard. A per-protocol latch
or finalizer ordering alone cannot establish the required cleanup outcome.

Implementation acceptance requires actual database/sql fixtures proving: partial
preparation and cancellation clean all owners; asynchronous cancellation-discard
reports injected statement/physical-close failure before final completion; active
Rows finish before catalogue release; fully pinned pools reuse the parents; and
both target finalizers preserve prior errors. These are gwindor's existing
lifetime/failure acceptance boundaries, not a new transport project.

River finalization releases the catalogue immediately before Database.Close.
MariaDB's existing DropOwned closes its workload pool before its administrative
DROP; preserve that actual ordering and retained errors. The common binding keeps
identical SQL, isolation, mix, transaction wrappers and retries. Protocol framing
and Java handle ownership are unchanged.

The user directs local harness delivery with no publication destination for now.
Record its local branch and immutable commit; absent remote publication is no
longer a blocker. No cross-database performance claim is admitted.

The current-source negative fixture now reproduces both release-error loss and
the asynchronous-discard race using real database/sql and the unchanged River
driver: idle/pinned release returns nil at Database.Close; four cancelled pinned
transactions followed by replacement-parent reuse prune the held physical close,
and Database.Close returns before its injected failure. No Go-internal hooks or
new production instrumentation are used. Log:
`/private/tmp/river-performance-20260914/gwindor-cleanup-baseline.log`.
The independently reviewed contract and failure matrix admit implementation;
positive ownership, cleanup and workload evidence remain gwindor's delivery gate.
