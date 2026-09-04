# River TPS performance review and priority order

Status: P1 implementation and evidence in progress  
Date: 2026-09-03  
Scope: River JDBC TPC-C engineering workload and the proposed transaction
optimization route

## Executive decision

The retry storm and the lock-residence collapse are the first performance and
correctness priorities. Protocol collapse, result encoding, buffer sizing,
index changes, group-commit queue tuning, and row-at-a-time removal must not
consume multi-day implementation effort until the observed lock-order cycles
are corrected and commit preparation no longer serializes useful concurrency.

The present evidence supports this order:

1. **P0: explain and eliminate pathological lock cycles and ordering.**
2. **P1: shorten the serialized commit window and prove durable grouping.**
3. **P2: prove one transaction-family protocol collapse with a paired A/B.**
4. **P3: optimize demonstrated CPU, copy, allocation, and row-publication
   hotspots.**
5. **P4: run the normative Alpha3 capacity gate.**

The current transaction route remains useful architectural background, but it
is not a validated performance implementation order. No 50x performance claim
is supported yet.

## TPC-C implementation guidance applied to River

`tcpc-perf-notes.md` is accepted as a catalogue of established TPC-C
techniques, not as evidence that every technique is currently required by
River. Its central priorities agree with the measurements in this review:
fine-grained concurrency control, short hot-row lock residence, durable group
commit, resident hot data, prepared execution, and bounded concurrency. The
following matrix makes each recommendation evidence-gated so that generic
benchmark advice does not trigger an unrelated subsystem rewrite.

| Technique from the notes | River evidence and decision | Plan placement |
| --- | --- | --- |
| Fine-grained locks, update intent, consistent order, fast victim cleanup | River already has key and range locks, but captured scheduler-enforced cycles show inconsistent resource order and conversion hazards. Keep fine granularity; do not substitute table escalation. | P0 |
| Hot-row and hot-key handling | Warehouse, district, stock, customer, and recent-order resources are deliberately hot. Measure wait time and holders by logical resource class; shorten work under those locks and localize counters without weakening transactional rollback. | P0, then P3 only for demonstrated latch/CPU cost |
| Sequential WAL and group commit | Current commits are durable, but successful groups have cohort size one and preflight occurs while transaction locks remain held. Prepare immutable transaction work before the short shared publication window, then append and force a real cohort once. | P1 |
| Buffer-pool residency and sizing | No measured cache-miss, eviction, or foreground data-write evidence currently explains the regression. Add bounded hit/miss, eviction, dirty-page and foreground/background I/O accounting before changing capacity or policy. Do not hard-code the notes' example memory percentages. | P3 when admitted by evidence |
| Minimal indexes and efficient access paths | TPC-C indexes must be audited by actual lookup, touched-page, maintenance and split costs. Do not add or remove an index from generic advice alone. | P3 |
| Prepared plans, specialized point execution and reduced round trips | New Order already uses one prepared transaction program; Payment and other JDBC families remain chatty. Preserve full transaction semantics and use a paired family-level A/B. | P2, then P3 |
| Compact rows, efficient inserts and page splits | Relevant to New Order only if measured page allocation, split, copy, or cache costs are material after P0/P1. | P3 |
| Bounded workers and connection concurrency | More terminals currently add blocked time rather than throughput. Use terminal sweeps to find the knee, but treat admission tuning as a guardrail rather than a substitute for fixing lock order. | P0/P1 evidence; P3 tuning |
| Delivery batching and ordered processing | Delivery holds locks across ten districts and is now a material retry source. Keep ascending district order, classify its remaining resource inversions, then evaluate set-oriented or program execution without changing oldest-order semantics. | P0, then P2/P3 |
| Smooth checkpoints and background I/O | Checkpoint, WAL, data-file and maintenance I/O must be attributed separately. No checkpoint may overlap a measured sample unless that is the declared test contract. | P1 telemetry; P3 if foreground interference is measured |
| Partitioning, lock-table sharding, affinity and NUMA | The current profile is dominated by parked lock/commit waits, not a measured global lock-table mutex or NUMA cost. Defer until multi-warehouse CPU/latch evidence identifies the shared structure. | P4 scaling follow-up |

The notes' recommended sequence is therefore specialized for the current River
failure: lock ordering and hot-resource residence first; durable commit
preparation/grouping second; protocol and prepared execution third; then
buffer, index, page, worker, checkpoint, and locality work only when their
named measurements cross a predeclared threshold. Durability, common
isolation, required indexes, and complete TPC-C behavior remain constraints,
not tuning variables.

Instrumentation added for these gates follows
`docs/plans/river-external-observability-tool-outline.md`: retain primitive,
bounded aggregate counters on accepted low-cost paths; gate detailed events,
fingerprints, stack/JFR capture, and exemplars behind an explicit finite
capture; and perform formatting or OpenTelemetry export in the external
utility. Disabled detailed capture must not read clocks, allocate, hash, or add
an application protocol exchange. This allows the measurements below to become
client-facing diagnostics without making observability the new TPS limiter.

## Evidence collected

These are local diagnostic measurements, not promotion results. They used the
tiny nonstandard dataset, one warehouse unless stated otherwise,
`NO_WAIT_STRESS`, seed `123456789`, one second of warmup, ten seconds measured,
and no JFR.

### Terminal scaling with the standard transaction mix

| Terminals | Committed | Whole-transaction retries | Retries per commit | TPS |
| ---: | ---: | ---: | ---: | ---: |
| 2 | 598 | 11 | 0.02 | 59.8 |
| 3 | 604 | 189 | 0.31 | 60.4 |
| 4 | 573 | 329 | 0.57 | 57.3 |
| 10 | 599 | 1,176 | 1.96 | 59.9 |

Concurrency above two terminals produced retries instead of useful throughput.
At ten terminals, New Order accounted for 1,134 of 1,176 retries (96.4%). The
program reported 1,181 failures at its first `warehouse-read` step. Payment had
five retries.

The managed server reported 1,316 deadlocked lock requests in that run. This
counter is server-lifetime scoped, so it also includes load, preflight, warmup,
drain, and checkpoint activity. It supports the lock diagnosis but cannot be
equated directly with measured transaction retries.

### Transaction-mix discriminators

Temporary benchmark-only changes were run in an isolated worktree and then
discarded. Each ten-terminal measurement retained the benchmark's initial
forced-family transactions, so the first two rows are dominant-family rather
than mathematically pure workloads.

| Diagnostic mix | Result |
| --- | --- |
| New Order-dominant | 408 New Order commits and 11 New Order retries |
| Payment-dominant | 1,239 Payment commits with zero Payment retries; the two forced New Orders each exhausted all 32 attempts at `warehouse-read` |
| 50/50 New Order/Payment | 333 New Order commits and 1,428 New Order retries; 365 Payment commits and 9 Payment retries |

The 50/50 run reported 1,516 New Order `warehouse-read` failures and 1,676
server-lifetime deadlocked lock requests. This reproduces the storm without a
meaningful Delivery, Order Status, or Stock Level contribution.

These results establish a cross-family New Order/Payment interaction. They do
not yet identify the exact wait-for cycle or prove whether the detector,
scheduler, cleanup path, or SQL lock order is at fault.

### Isolation discriminator

Before the P0 benchmark controls were implemented, transaction programs began `SERIALIZABLE` in
`SqlSessionExecutionCoordinator.beginProgram`. Ordinary JDBC transactions
defaulted to `REPEATABLE_READ`, and `TpccSession` did not override that default.
Consequently, the original standard benchmark mixed isolation levels within
each terminal:

- New Order and Stock Level programs run `SERIALIZABLE`;
- Payment, Delivery, and Order Status run `REPEATABLE_READ`.

The warehouse operations are the immediate point of interaction:

- New Order first executes `SELECT w_tax FROM warehouse WHERE w_id=?`;
- Payment first executes `UPDATE warehouse SET w_ytd=w_ytd+? WHERE w_id=?`.

Diagnostic attempts to make every JDBC family, and then Payment alone,
`SERIALIZABLE` caused lock timeouts during warmup. Therefore changing the
benchmark isolation setting by itself is not an accepted lock fix. The timeout
is additional evidence that lock waiting or cleanup needs explicit
investigation. It is not a reason to retain mixed isolation after that defect is
corrected: P0 exit requires one declared isolation contract for both JDBC and
program execution.

### Mature-engine policy check and P0 implementation checkpoint

The lock policy is not being invented from TPC-C trial and error. The relevant
established designs separate row-lock memory pressure, writer serialization,
deadlock detection, and queue fairness:

| Engine | Established policy relevant to River | River conclusion |
| --- | --- | --- |
| MySQL/InnoDB | Uses row, record, gap, and next-key locking; its compact row-lock representation avoids lock escalation. It schedules contended waiters with CATS rather than unconditional FIFO: transactions blocking more work receive greater weight, with wait age as a tie-break. Conflicting queued writers still prevent an endless stream of readers from bypassing them. | A one-row warehouse contention cycle is not evidence that River needs row-to-table escalation. River needs an explicit starvation-safe scheduling rule, not merely queue order. |
| MariaDB/InnoDB | Exposes a lock-wait timeout, but deadlock detection reports a deadlock without waiting for that timeout. | A warmup timeout is a liveness failure, not a normal way to resolve a known cycle. |
| PostgreSQL | Row-level locks block writers and lockers of the same row, while ordinary reads use MVCC and are not blocked by row locks. PostgreSQL does not retain a bounded in-memory row-lock list that needs escalation. Its deadlock model distinguishes owner-induced hard edges from incompatible earlier-waiter soft edges and first tries a proved wait-queue reordering for a soft cycle before aborting a transaction. | Serializable predicate protection and write serialization should be analysed separately. River must likewise distinguish mutable fairness policy from immutable owner conflicts. |
| Ingres | The optimizer can start with a table lock when it estimates a query will exceed `MAXLOCKS`; otherwise exceeding `MAXLOCKS` or the per-transaction limit escalates row/page locks to a table lock. The documented system default is 50 locks, and the documentation warns that escalation can itself introduce deadlocks. | Escalation is a bounded-resource and granularity policy to consider later, not a repair for River's two-resource fairness cycle. It requires a complete lock hierarchy and intention-lock protocol. |
| SQL Server | Uses update (`U`) locks so a transaction intending to update can avoid a shared-to-exclusive conversion deadlock; only one transaction can hold the update lock on a resource. It escalates for lock-count or lock-memory pressure, not as routine deadlock recovery. | River's existing `UPDATE` mode should be evaluated for real read-then-write paths, but it does not explain the captured tuple-key/range cycle. |

Primary references: [MySQL InnoDB locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-transaction-model.html),
[MySQL lock-escalation glossary entry](https://dev.mysql.com/doc/refman/8.4/en/glossary.html),
[MySQL CATS scheduling](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-scheduling.html),
[InnoDB lock-scheduler design](https://dev.mysql.com/doc/dev/mysql-server/latest/PAGE_INNODB_LOCK_SYS.html),
[MariaDB InnoDB lock timeout](https://mariadb.com/docs/server/server-usage/storage-engines/innodb/innodb-system-variables),
[PostgreSQL explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html),
[PostgreSQL lock-manager and soft-deadlock design](https://github.com/postgres/postgres/blob/master/src/backend/storage/lmgr/README),
[Ingres locking-level selection](https://docs.actian.com/ingres/11.2/DatabaseAdmin/How_the_Locking_Level_is_Determined.htm),
[Ingres `MAXLOCKS`](https://docs.actian.com/ingres/11.2/DatabaseAdmin/The_MAXLOCKS_Value.htm), and
[SQL Server update locks](https://learn.microsoft.com/en-us/sql/relational-databases/sql-server-transaction-locking-and-row-versioning-guide?view=sql-server-ver17).

River's resulting policy direction is:

1. Do not add escalation to P0. Keep lock-state bounds explicit and fail with a
   bounded resource status until River has a real row/range/table hierarchy,
   intention locks, and tests proving that escalation cannot violate serializable
   predicate protection.
2. Classify every wait-for edge as a hard owner conflict, conversion-priority
   dependency, or soft fairness dependency. Scheduler admission, graph creation,
   diagnostics, and graph self-validation must call the same grant predicate.
3. Resolve a cycle containing a mutable soft edge by a narrowly proved bypass or
   queue reorder when doing so preserves writer-starvation bounds. Select a victim
   only when the remaining cycle is hard or no valid ordering exists.
4. Exercise River's existing `UPDATE` mode on deterministic read-then-write
   conversion tests before routing SQL to it. Adopt it only where it reduces
   conversion cycles while preserving `FOR UPDATE` semantics for point and range
   access.
5. Treat contention-aware scheduling such as CATS as a measured post-correctness
   policy candidate. It must beat the simple policy on the P0 discriminator and
   standard mix without introducing starvation, unexplained retries, or unbounded
   graph work.

The bounded River trace identified a repeated, scheduler-valid two-node cycle:

1. Transaction A holds an exclusive tuple-key lock.
2. Transaction B queues an exclusive request for that tuple key.
3. Transaction A requests a shared overlapping tuple range.
4. Strict interval FIFO makes A wait behind B, although B cannot advance until
   A releases the tuple key.

This is a fairness-policy defect, not a false detector edge and not lock
escalation. The interval-path correction preserves ordinary FIFO but excludes
an earlier waiter as a fairness predecessor when that waiter is actively
blocked by the requesting transaction. Its scheduler, blocker graph, and cycle
self-validator use the same predicate. Existing compatible-reader FIFO tests
remain in place, and a regression proves that the owner completes and then
wakes the queued writer without a victim selection. This is not yet the whole
P0 fix: exact-resource scheduling still admits only the queue head, so the same
grant-policy rule must be proved for exact FIFO and conversion-priority paths
before P0 acceptance.

With one common `SERIALIZABLE` benchmark contract, the three-terminal Payment
discriminator completed 109 measured transactions at 54.5 TPS with zero
retries, errors, or timeouts. A later exact-default `tools/tps-test.sh` run
completed 584 measured commits at 58.4 TPS with zero errors and zero timeouts.
Its 22 measured retries were all reconciled `DEADLOCK` outcomes: 20 Stock Level
and two Delivery. Server-lifetime counters recorded 24 deadlock victims, so two
warmup victims remain outside the measured client total; phase-delta server
snapshots are therefore required before claiming complete reconciliation.
These are diagnostic runs, not completion of the P0 scaling gate.

Two short, deliberately retry-constrained checkpoints further narrow the
remaining work. With `maximum_attempts=4`, a three-terminal 50/50 run completed
173 counted commits at 57.7 TPS with no measured retry or error; the server
still recorded one warmup deadlock. A four-terminal standard-mix run completed
161 counted commits at 53.7 TPS with nine measured deadlock retries (one New
Order, four Delivery, four Stock Level) and no exhausted transaction; its
server-lifetime total was 12 victims. These three-second measurements are not
capacity evidence, but they show both that the old cross-family storm is no
longer the dominant failure and that lifetime-only server counters cannot
support exact retry reconciliation.

### Deadlock-victim rollback cascade found and corrected

The later phase-correlated instrumentation exposed a second, independent P0
defect that explained the previously "insane" retry multiplier. A five-second
ten-terminal run selected only three measured deadlock victims but reported 478
whole-transaction retries. One Delivery attempt first returned `DEADLOCK`; its
next 31 attempts all returned `CONFLICT` at the first operation. The same shape
then repeated on the affected terminal for later logical transactions.

The causal chain was:

1. the detector correctly selected a victim, cancelled its queued request, and
   released its holdings;
2. the transaction remained active and rollback-only until the client issued
   `ROLLBACK`;
3. the request carried the same opaque attempt diagnostics as the failed
   statement, but updating its step was rejected because the lock transaction
   was frozen in `DEADLOCK` state;
4. the server therefore rejected the request before dispatching `ROLLBACK`;
5. JDBC unconditionally suppressed `CONFLICT` from `ROLLBACK`, even though its
   local state said a transaction was active;
6. each later attempt selected a new diagnostic tag, which the still-active
   transaction correctly rejected, creating a bounded 32-attempt conflict
   cascade without doing transaction work.

A failure-only active-snapshot capture proved the storage consequence. Warmup
victim transaction 6053 remained at visible commit sequence 232 while current
transactions reached sequence 474. It pinned approximately 3,872 historical
page frames until direct-commit page-freeze admission returned
`RESOURCE_EXHAUSTED`. The deadlock event and retained snapshot had the same
transaction ID, generation, attempt tag, step tag, and epoch; this was not an
inferred association.

The correction preserves the original diagnostic tag on a deadlock victim and
allows same-attempt diagnostic requests to pass without mutating the frozen lock
record, so terminal rollback can execute. A different attempt tag remains
`CONFLICT` until rollback. JDBC no longer hides any failed rollback that it
believed was active. Focused SQL and loopback JDBC tests force a deadlock, prove
the terminal request behavior, roll the victim back, and reuse its session.

After that correction, the required default `tools/tps-test.sh` path completed
the ten-second standard mix with 343 measured commits, 34.3 TPS, 24 retries,
zero errors, no timeout, and exact 24-to-24 measured deadlock/client-retry
reconciliation. There were no conflict cascades or page-frame admission
failures. This is a correctness checkpoint, not the complete P0 scaling gate:
the 24 measured deadlocks still exceed the 5% performance-regression guard and
must be classified by cycle signature and family.

### Throughput regression audit after rollback correction

The apparent 58.4-to-34.3 TPS regression must not be attributed to the
deadlock-victim rollback correction without a reproducible A/B. A later run
used the exact command and seed recorded by the 58.4 TPS artifact. It completed
at 39.2 TPS with 22 measured retries, exactly the same retry count as the old
run. Both runs used 5.7 JDBC requests per transaction attempt. The slower run
also took 11.285 seconds to load the fixed dataset, versus 6.965 seconds in the
old run, so the regression affects uncontended work below the retry layer.

A current one-terminal run reinforces that conclusion: it completed at 38.4
TPS with zero measured retries. Ten terminals therefore add almost no aggregate
throughput on the one-warehouse workload. In the matched ten-terminal run,
workers accumulated 98.9 seconds of blocked lock time during a ten-second
interval. Direct commits spent approximately 11.3 ms per commit in preflight
and 3.9 ms per commit forcing WAL while transaction locks remained retained.
Those figures identify lock residence and commit service time as the current
ceiling; they do not yet identify which post-baseline code change increased
that service time.

The exact regression cannot be reconstructed retrospectively. Both artifacts
record commit `18b1859` with `git.dirty_state=dirty`, but their status hashes
differ and the old artifact did not preserve the patch contents or a source-tree
content fingerprint. The intervening changes include commit/WAL telemetry,
phase-correlated lock diagnostics, protocol correlation, and deadlock-terminal
cleanup. Most cleanup logic is failure-only, and sampled profiles do not support
blaming it for the successful-path slowdown. The 58.4 TPS result is therefore a
useful historical observation, not an attributable code baseline.

`tools/tps-test.sh` now records start and finish workspace-content SHA-256
fingerprints and whether the workspace changed during the run. Future
performance comparisons require matching source fingerprints, configuration
fingerprints, deterministic seeds, and multiple interleaved samples. A single
ten-second dirty-worktree result is diagnostic only.

### Failure-mode displacement observed

Later measurements demonstrate why throughput cannot be the acceptance signal
for a contention correction. After unchanged secondary-key protection was
removed from payload-only updates, a ten-terminal standard-mix run completed at
55.3 TPS while measured retries increased from 20 in the preceding diagnostic
sample to 53: 38 New Order, ten Delivery, and five Stock Level. The successful
work became faster, but the run did not pass P0 because contention outcomes
worsened and the remaining cycle shapes had not been eliminated.

A subsequent, deliberately narrow attempt to acquire the base-row lock before
fetching an exact singleton made the displacement more obvious. A five-second,
ten-terminal New Order discriminator completed 184 transactions at 36.8 TPS
with 246 measured retries and four retry-exhausted transactions. All 277 warmup
and measured victim selections had one normalized cycle fingerprint; one
separate load-phase victim had another fingerprint. The measured fingerprint's
enforced edges were:

1. step 8 requested `UPDATE` on a stock base key while step 9 held that base
   key `EXCLUSIVE`;
2. step 9 requested `EXCLUSIVE` on the tuple source while step 8 retained the
   tuple source `SHARED`.

This is a source/base acquisition inversion, not evidence that the attempted
base-first policy was partly successful. Moving base acquisition earlier made
one side of the same valid scheduler cycle easier to reach. The experiment was
rejected and reverted. Its retained artifact is
`/private/tmp/river-tps-neworder-lock-before-read`; it is diagnostic evidence,
not a baseline or a candidate implementation.

These runs establish a general rule for P0: removing one obstruction can expose
the next obstruction, change its frequency, or move waiting to another phase.
A slice passes only when its named failure mode is eliminated or brought below
its declared bound without an unexplained increase in any replacement failure
mode. TPS and latency are evaluated only after that failure-mode displacement
check passes.

The corrected source-order candidate then made the distinction concrete. Under
`SERIALIZABLE`, it acquires an `EXCLUSIVE` tuple-source lock at scan admission
only when the SQL policy requests a locking operation and the relational layer
independently proves encoded inclusive equal bounds over every part of the
physically chosen unique key. Weaker isolation levels retain their existing
base-row locking behavior; this is an explicit serializable predicate/source
policy, not an unconditional extra lock. Semantic `EXACT_ONE` classification
remains separate: a singleton query reached through a non-point access path
remains a scan. The final matching five-second New Order discriminator
completed 207 measured transactions at 41.4 TPS with zero measured retries,
zero retry exhaustion, no measured deadlock fingerprint, exact outcome
reconciliation, and zero terminal cleanup state. The prior
`68c62ac8f33547b6` source/base fingerprint was absent.

That targeted pass did not pass P0. A subsequent ten-terminal standard-mix run
completed 577 counted commits at 57.7 TPS but produced 70 reconciled deadlock
retries: five New Order, 51 Delivery, and 14 Stock Level. The measured epoch
contained five fingerprints: `fc2cdaf98a164156` (17 victims: 14 Stock Level
and three New Order), `19e36e09a043a757` (34 Delivery),
`8ee935a16c362b47` (one Delivery and two New Order),
`180e8eb3a7cd0a80` (15 Delivery), and `2e424f28959152df` (one Delivery), with
no instance of `68c62ac8f33547b6`. The edge exemplars classify the dominant
replacement modes as Delivery base/source and base-lock conversion cycles,
plus a Stock Level/New Order tuple-source ordering cycle. They are next
blockers, not regressions to be folded into an average TPS claim. Artifacts are
retained at
`/private/tmp/river-tps-neworder-serializable-source-first` and
`/private/tmp/river-tps-standard-source-first-cause`.

The first attempted standard-mix validation also exposed a benchmark reporting
defect: terminal cleanup exceptions could mask the original measured-phase
failure and make the tool report `CLOSED` without its causal exception. The
runner now preserves the execution exception as primary and attaches cleanup
failures as suppressed evidence. This does not classify the first transient
connection loss retrospectively; that artifact remains an invalid run at
`/private/tmp/river-tps-standard-source-first`.

The next Delivery-specific candidate retains the same source range and
lifetime but changes the `SERIALIZABLE` locking-source mode for a nonexact
index access from `SHARED` to River's existing `UPDATE` mode. Ordinary scans
remain `SHARED`, and physically exact unique locking sources remain
`EXCLUSIVE`. The semantic command policy and physical access classification
meet in one `SqlDescriptorIndexAccess` decision consumed by both streaming and
singleton/mutation execution; no scheduler predicate or second executor was
added. A deterministic oldest-order test proves that a second Delivery-shaped
worker blocks at scan admission, takes its snapshot after handoff, and sees the
next row after the first worker deletes and commits. An empty-prefix test proves
that a matching insert blocks while an adjacent district remains independent.

A ten-second, ten-terminal standard-mix diagnostic then completed 550 counted
commits at 55.0 TPS with 28 exactly reconciled measured victims/retries, zero
errors, zero timeouts, zero exhausted retries, and zero terminal cleanup state.
The three predeclared Delivery fingerprints and the earlier mixed
`8ee935a16c362b47` fingerprint were absent. The measured epoch instead contained
`fc2cdaf98a164156` (23 Stock Level victims), `6114f3254a4eba2c` (one New Order
and one Stock Level), `fba619fb035f3fd5` (one Stock Level), and the novel
`23ea8bbd272f3b6a` (two Delivery victims). Load and warmup each had one separate
victim, accounting exactly for the server-lifetime total of 30. This is strong
evidence that source `UPDATE` removes the dominant Delivery/Delivery topology,
but it fails the displacement gate because the new cross-family Delivery/New
Order topology has not been eliminated. Stock Level is now the dominant
measured blocker with 25 of 28 retries. The artifact is retained at
`/private/tmp/river-tps-standard-source-update-intent`.

Independent concurrency review also limits the claim: a retained broad
`UPDATE` predicate serializes overlapping locking workers and preserves phantom
protection, but it does not by itself establish a complete engine-wide source
order. An ordinary serializable reader may still hold a compatible source
`SHARED` lock while waiting for a base row that the writer owns, then block the
writer's selected source-key `EXCLUSIVE` mutation. The generic complete order
to evaluate is predicate `UPDATE`, selected source key `EXCLUSIVE`, then base
row `EXCLUSIVE`, using canonical cursor-owned key bytes without per-row
allocation. Until that path and its liveness tests exist, source `UPDATE` is a
narrow Delivery correction and not the completion of the second P0 slice.

The new native discriminators make the residual priorities sharper. The
ten-second New Order/Delivery 50/50 run completed 658 counted commits at 65.8
TPS. Delivery completed 352 transactions with zero retry; all eight measured
retries selected New Order as victim and shared fingerprint
`23ea8bbd272f3b6a`. Its two active-owner edges reconstruct a real cross-family
order inversion: New Order retains a customer read before requesting the
`new_order` insert source, while Delivery retains the `new_order` predicate
before requesting the customer update. The old Delivery/Delivery fingerprints
remain absent, but this application-order cycle is now a named canonical
write-set problem rather than an unexplained replacement. There were no
timeouts, exhausted transactions, overflows, or cleanup leaks. The artifact is
`/private/tmp/river-tps-new-order-delivery-update-intent`.

The corresponding New Order/Stock Level 50/50 run failed much more severely:
397 counted commits at 39.7 TPS, 974 measured retries (206 New Order and 768
Stock Level), and six additional Stock Level attempts that exhausted all 32
attempts while draining. The server epoch recorded 980 victims/outcomes and
cancelled requests, matching the 974 measured plus six drain outcomes. Its
dominant fingerprint was `fc2cdaf98a164156` with 750 victims; the same epoch
also contained `fba619fb035f3fd5` (95), `6114f3254a4eba2c` (52),
`fa4f0a2fc41968b7` (35), `4b2bdbafc33df868` (34), and five smaller shapes
totalling 14. No detector self-validation, fingerprint, correlation, cycle-edge,
or cleanup failure occurred, but the drain exhaustion is a liveness failure and
invalidates the run. The artifact is
`/private/tmp/river-tps-new-order-stock-level-source-update-intent`.

That run also found a benchmark gate defect: diagnostic execution returned
`result=completed` despite the drain-time retry exhaustion. The terminal
outcome gate now rejects retry exhaustion or unexpected failure in either the
measured or drain side of the cutoff; expected TPC-C business rollbacks remain
valid. Server metrics still use one epoch across measured completion and drain,
so the report must retain the client-side cutoff classification and should add
a distinct drain epoch before claiming phase-exact server reconciliation.

The external Go harness remains useful as a successful-path mechanism check,
but it cannot pass or fail this P0 gate. With its `READ COMMITTED` plus explicit
`FOR UPDATE` contract, one matched New Order/Payment sample rose from 41.27 to
55.72 committed TPS while retries rose from zero to 76. After the serializable
source policy was explicitly scoped away from that isolation level, another
sample reached 57.99 TPS but still had 79 retries and two failed New Order
outcomes. The harness does not currently retain River cycle fingerprints or
reconcile client retries to server outcomes, and these runs span other dirty
workspace changes. They therefore show a failure-mode warning, not causation by
the source-order slice. Native phase-correlated diagnostics remain normative
for P0; the harness remains a throughput-regression guard until its compare and
failure-correlation support is available.

One retained harness failure produced two New Order `INVARIANT_BROKEN` commit
outcomes followed by widespread `FENCED` results. It does not prove that a
deadlock rollback fenced the store: harness errors are concatenated by worker,
not retained as a global timeline, and the server log/metrics were removed with
the ephemeral server. The stronger static explanation is a two-member hybrid
group failing prepared publication after append/force, which assigns the same
indeterminate outcome to both members and fences later admission. P0 must
therefore add a deterministic intersection test covering a near-full tuple
leaf, a structural split, two base-plus-tuple group members, phase-by-phase
publication, complete cleanup, a successful subsequent commit, and reopen
verification. Populate until tuple preflight predicts a split; do not encode a
benchmark-sized row count into the test.

### Scalable probe ordering and complete application order

The Stock Level correction was rebuilt without the rejected 300-row carrier.
The production operator now writes its compact key/predicate shape to the
existing statement-owned paged row store, sizes sort-run page reservation from
the actual ordinal cardinality, and spills through the configured external
order path. A 2,200-row test crosses the resident run boundary; duplicate and
missing keys, NULLs, predicates spanning both join roles, signed composite
keys, rollback cleanup, and the existing aggregate-capacity boundary are
covered without a workload-derived row limit.

The resulting ten-terminal New Order/Stock Level discriminator completed at
48.2 TPS with zero measured retries, failures, timeouts, or resource
exhaustion. A later run after the New Order order correction completed at 42.5
TPS with the same zero-failure result. These short figures are not an
improvement/regression claim; the result that matters for this P0 slice is that
the former 974-retry storm and `fc2cdaf98a164156` family were absent without a
replacement measured fingerprint. The first artifact is
`/private/tmp/river-tps-new-order-stock-level-key-ordered-v3`; the later check
is `/private/tmp/river-p0-final-new-order-stock-level-v1`.

The first New Order/Delivery application-order change moved only the customer
read. It removed `23ea8bbd272f3b6a` but exposed
`24a4e4741b2ffb32`: New Order still inserted `orders` before requesting the
`new_order` source while Delivery held `new_order` before updating `orders`.
Reversing the two inserts was rejected immediately because the declared
`new_order` foreign key correctly requires its `orders` parent.

The accepted order is therefore explicit and complete: New Order performs an
exact `SELECT ... FOR UPDATE` of its not-yet-present `new_order` key, inserts
the `orders` parent, inserts the `new_order` child, and only then reads the
customer. This reserves the contested source key without installing a child
before its parent, remains one `EXECUTE_PROGRAM` exchange, and uses the same
SQL/locking/rollback path as other program steps. The reserve operation has its
own failure classification rather than being counted as an insert failure.

The ten-terminal New Order/Delivery discriminator then completed 614 counted
commits at 61.4 TPS with zero measured/drain retries, failures, timeouts,
exhaustions, or overflows; both target fingerprints were absent and terminal
cleanup was empty. The artifact is
`/private/tmp/river-tps-new-order-delivery-canonical-v3`. The matching standard
mix completed 547 counted commits at 54.7 TPS with zero retries or errors at
`/private/tmp/river-tps-standard-canonical-v4`. Ten-terminal New Order/Payment
also completed with zero retries at 60.0 TPS.

Two-, three-, and four-terminal New Order/Delivery checks were all clean at
63.2, 64.0, and 67.2 TPS. Corresponding standard runs were clean at 57.8,
34.2, and 58.4 TPS; an exact repeat of the anomalous three-terminal point
reached 62.5 TPS. Preserve the 34.2 sample as host-variance evidence: it is not
a monotonic scaling result and is another reason not to promote one short TPS
sample into an architectural conclusion.

Every current server-lifetime report also contains one epoch-zero
`d9d2174596fe16c4` victim. This is the deliberately opposing district-lock
preflight probe, which requires exactly one victim to prove deadlock rollback
and session reuse; it is not a workload retry. The probe now assigns both
transactions opaque attempt tags, initial/opposing step tags, and dedicated
epoch 3. `/private/tmp/river-preflight-correlation-v1` records its one victim,
one outcome, one queued cancellation, two released holdings, two enforced
active-owner edges, and nonzero tags for both participants while measured epoch
2 remains at zero victims.

One matched external harness attempt produced an eligible MariaDB control at
650.9 TPS but an ineligible River sample at a raw 41.0 TPS because the measured
phase reused a transport closed by warmup cancellation. The harness River
driver marks its protocol closed but does not implement `driver.Validator`, so
Go's SQL pool can return that dead physical connection and the next prepare
reports `driver: bad connection`. The River result must not be used as a ratio.
The harness should discard transport-failed connections, prepare workers
outside phase timing, and prepare only the union of statements used by the
selected families while retaining full catalogue identity as evidence.

## Conclusions supported now

1. The retry storm is real and reproducible.
2. It appears at a concurrency threshold between two and three terminals.
3. It is overwhelmingly charged to New Order's first warehouse read.
4. It requires sustained interaction with Payment; neither dominant-family
   workload produces the same pattern independently.
5. Retry-heavy TPS is not a useful measure of CPU execution capacity.
6. JDBC request count is not the first limiter in the failing mixed workload.
   Payment-dominant execution reached 124.5 TPS while retaining approximately
   10.2 requests per Payment attempt.
7. Increasing retry attempts or tuning retry backoff would conceal the defect
   and is not an optimization.
8. The captured Payment cycle was caused by strict interval FIFO making an
   active lock owner wait behind a writer that the owner itself blocked.
9. Lock escalation would not resolve that cycle and is not part of the P0 fix.
10. A deadlock victim's diagnostic context previously prevented its own
    rollback request from reaching SQL dispatch, and JDBC converted that
    cleanup failure into repeated transaction attempts.
11. One deadlock must produce one client disposition; a larger retry multiplier
    is itself evidence of lifecycle or correlation failure, not ordinary
    contention.
12. After the rollback correction, a matched-seed run retained the old run's
    22 measured retries but fell from 58.4 to 39.2 TPS, and a one-terminal run
    reached only 38.4 TPS with zero measured retries. The reduced throughput is
    therefore below the retry layer.
13. In the matched ten-terminal run, lock waits accumulated 98.9 seconds of
    blocked time in approximately ten measured seconds. Successful group
    commits formed 199 cohorts containing 199 transactions. Direct preflight
    averaged approximately 11.3 ms per commit while locks remained retained.
14. New Order originally executed stock operations in input line order. The
    transaction program now establishes deterministic supply-warehouse/item
    order while retaining original line metadata and invalid-item semantics.
15. Canonical Stock Level dependent-primary probing removes the reproduced
    Stock Level/New Order cycle without a workload-derived result cap.
16. New Order/Delivery requires complete `new_order`, `orders`, then customer
    acquisition order; moving only the final customer read merely displaced the
    cycle.
17. The current discriminator and standard samples have zero measured retry,
    but this does not establish the remaining successful-path ceiling or a
    MariaDB parity ratio.
18. Exact tuple-page occupancy can be decided from the validated slot-table
    boundary and `freeEnd`; rescanning and decoding every resident key during
    insert admission was unnecessary.
19. A writable copy-on-write page can reuse complete validation only through a
    single-use capability bound to the exact page object, generation, schema,
    shape, type, and exclusive writable borrow. Generation equality alone is
    not a validation proof.
20. Response encoding can copy the result carrier's canonical UTF-8 directly.
    Decoding to UTF-16 and then re-encoding the same cell was duplicate work,
    not a required protocol boundary.

## Facts not established

The following remain open after the first resource-level diagnosis:

- phase-exact correlation for the intentional preflight deadlock probe and the
  remaining two-, three-, four-, and ten-terminal discriminator matrix needed
  for formal P0 promotion;
- which reclaim, compile, validation, page-freeze, append, or publication
  operation dominates the measured commit-preflight time;
- whether real multi-transaction cohorts form after lock ordering and commit
  preparation stop serializing arrivals;
- whether foreground data-page I/O, cache eviction, page splits, or checkpoint
  interference contributes materially to longer runs or larger datasets;
- collapsing Payment to one request will materially improve mixed TPS after
  locking is corrected;
- the sampled JFR methods are sufficiently dominant to justify optimization.

No implementation should be selected by assuming one of these statements is
already true.

## P0: lock-cycle validation and correction

### Objective

Explain the original New Order `warehouse-read` retries and every remaining
Delivery, Stock Level, and New Order victim in deterministic workload sweeps.
Remove scheduler-policy defects and resource-order inversions without
weakening isolation, coarsening routine locking, or hiding retries.

### Selected lock and hot-resource remediation

P0 uses established TPC-C practice—fine-grained locks, update intent,
consistent acquisition order, short hot-row residence, and cheap victim
cleanup—but applies it to the exact River lock graph:

1. Define one sortable physical lock-resource key containing scope,
   namespace, lower key, upper key, and requested role. Document the required
   order between tuple ranges, tuple keys, index keys, and base-row keys.
2. For a transaction-program step whose point write set can be derived from
   bound arguments, collect the complete resource-admitted write set before mutation, sort it by
   that resource key, and acquire `UPDATE` intent in that order. Convert to
   `EXCLUSIVE` only when installing the mutation. Do not add a second executor
   or benchmark-family types to `river-tx`.
3. In New Order, process stock read-for-update and update pairs in canonical
   `(s_w_id, s_i_id)` order. Retain the original order-line number, input/result
   association, amount calculation, and invalid-item rollback semantics as
   separate metadata; sorting may not alter the logical transaction.
4. Make scan and mutation paths request overlapping tuple-range, tuple-key,
   index-key, and base-row protection in the same declared order. In
   particular, remove the Stock Level/New Order inversion demonstrated by the
   captured active-owner edges.
5. Preserve ascending district processing for Delivery and use its correlated
   step tags to remove any remaining resource inversion. Set-oriented Delivery
   work is a later optimization, not a P0 correctness shortcut.
6. Attribute blocked time, grants, victims, and retained holdings to generic
   resource classes. Use these counters to identify warehouse, district,
   stock, customer, and recent-order hotspots. A specialized counter or
   partition-local path is admitted only when it preserves commit/rollback and
   recovery semantics.

The first implementation slice is the complete resource-admitted canonical write set plus
New Order ordering. The second is the engine-wide tuple/index/base ordering
correction proved by the Stock Level and Delivery reproducers. Lock-table
partitioning, table escalation, MVCC replacement, and concurrency throttling
are not substitutes for these slices.

The displaced fingerprints split that second slice into two independently
gated changes:

1. For `fc2cdaf98a164156`, keep Stock Level as the existing two-step retained
   transaction program and one `EXECUTE_PROGRAM` request. Add an engine-generic
   dependent-primary-probe operator to the existing SQL block pipeline:
   materialize `(stock_w_id, stock_i_id)` keys in canonical typed SQL order in
   the statement-owned paged row store, spilling through the configured sort
   path as required, then probe the complete stock primary key in that order and
   feed the existing aggregate. This is a SQL physical operator, not program
   iteration, a protocol row variable, client-side N+1 probing, or a second
   transaction executor. Its gate is zero `fc2cdaf98a164156` victims with no
   replacement source-order fingerprint, one protocol request, identical
   result semantics, explicitly accounted ownership, and no steady-state
   allocation per source row or probe. The operator must not impose a hidden
   workload-derived row ceiling.
2. For Delivery fingerprints `19e36e09a043a757`, `180e8eb3a7cd0a80`, and
   `2e424f28959152df`, first add a New Order/Delivery discriminator and
   deterministic oldest-order contention test. Evaluate `UPDATE` source intent
   for nonexact `SELECT ... FOR UPDATE` scans so overlapping Delivery lockers
   serialize before base-row acquisition while ordinary readers remain
   compatible. Accept it only if the target base/source and conversion cycles
   disappear without starvation, a broader retained range, or a replacement
   queue-fairness cycle. `8ee935a16c362b47` crosses Delivery and New Order and
   must be reclassified after those two dominant families are removed.

### Minimum benchmark controls

Add explicit benchmark options for:

- execution mix: `standard`, `new-order`, `payment`,
  `new-order-payment-50-50`, and targeted New Order/Delivery and New
  Order/Stock Level discriminator mixes;
- JDBC isolation and program isolation, both printed in the report;
- one benchmark isolation-contract setting that is applied to every JDBC and
  transaction-program family and fails preflight if requested and effective
  isolation differ;
- measured-phase server counter snapshots;
- seed, terminal count, workload scale, durability, and scheduling profile;
- a diagnostic mode that can skip promotion mix requirements without changing
  transaction semantics.

The standard workload must retain its standard mix. Diagnostic mixes must be
labelled and must never be accepted as Alpha3 evidence.

### Required retry accounting and correlation

Before each benchmark attempt begins its server transaction, assign a unique
opaque attempt-correlation ID. Propagate it through the JDBC/session boundary
to transaction and lock diagnostics. A retried logical transaction receives a
new attempt ID linked to the same benchmark transaction sequence and increasing
attempt number.

`river-tx` must treat this value as an opaque diagnostic tag. It must not depend
on TPC-C families, benchmark steps, JDBC types, or benchmark phase types. The
benchmark/session boundary owns a bounded mapping from the opaque tag to
terminal, family, logical transaction sequence, attempt number, statement or
program step, and phase. If the step changes during an attempt, update or issue
the opaque diagnostic tag at that boundary rather than teaching the lock
manager what a New Order or Payment step means.

For every attempted transaction, report bounded counters by:

- family;
- statement or program step;
- exact internal `StatusCode`;
- committed, expected business rollback, retry exhausted, or failed outcome;
- measured phase versus warmup/drain.

Every diagnostic event must carry a generic metrics epoch and a
server-monotonic event sequence. The session/benchmark boundary resolves the
epoch to warmup, measured, or drain phase. Wall-clock timestamps may supplement
the sequence but must not be used to infer event order.

The following accounting must reconcile exactly:

- one attempt ID maps to one terminal server transaction outcome, except for an
  explicitly reported indeterminate connection/commit outcome;
- one client retry maps to the preceding retryable server outcome for the same
  attempt ID, and the next attempt has a distinct linked ID;
- a retryable server outcome produces either one client retry or one explicit
  retry-exhausted terminal outcome, never both;
- each deadlock victim selection maps to one victim transaction outcome, while
  the number of queued lock requests cancelled during victim cleanup is counted
  separately and is not treated as a retry count.

Report victim selections, victim transaction outcomes, queued requests
cancelled, client retries initiated, and retry-exhausted outcomes side by side.
Program failure counts that include work completed after the cutoff must be
reported separately from measured attempts.

### Required lock evidence

Use bounded aggregate accounting for every deadlock victim selection, plus
bounded exemplar records for detailed reconstruction. Do not use a ring of only
the first events as the evidence denominator: later cycles may differ, and
warmup events must not consume measured-phase exemplar capacity.

Assign every selected cycle a stable, versioned fingerprint derived from its
normalized scheduler-enforced edges: edge kind, resource namespace/scope,
request and held modes, queue relationship, and failed grant predicate. Use
relative participant positions and exclude concrete resource keys,
transaction IDs, event sequence, and wall-clock time so repeated instances of
the same cycle shape aggregate together. Maintain bounded counters by generic
metrics epoch and fingerprint covering every victim selection. Each entry must
include victim count, victim outcome counts, queued requests cancelled,
holdings released, and first/last event sequence. Fingerprint counts must sum
to the total victim-selection count for the epoch.

The fingerprint table must detect key collisions. If a novel fingerprint cannot
be admitted because its configured capacity is exhausted, increment an
explicit overflow counter and make the diagnostic run invalid for the P0 gate;
do not silently merge it into another signature. Keep at least one bounded
exemplar per admitted fingerprint and separate warmup, measured, and drain
quotas or stores. Aggregate counters are updated whether or not another
exemplar is retained.

Each lock-layer exemplar must contain enough information to reconstruct the
cycle without benchmark-specific types:

- generic metrics epoch, monotonic event sequence, cycle fingerprint, and
  victim-selection sequence;
- transaction ID, generation, start order, victim, and opaque diagnostic tag;
- requested resource scope and request mode;
- stable resource identity: scalar space/key, or tuple key ID plus a bounded
  key representation or digest;
- blocker transaction and its held mode, if any;
- queue kind and order for both requests, including whether the dependency is
  conversion priority or an ordinary FIFO predecessor;
- edge kind: incompatible active owner, conversion priority, or FIFO fairness
  predecessor;
- the precise scheduler grant precondition that prevents the waiter from
  advancing, including its inputs and evaluated result;
- all edges from the detected back-edge to the victim;
- victim cancellation status and number of holdings released and queued
  requests cancelled.

When assembling the benchmark report, join each opaque tag to the
session-owned mapping and render family, logical transaction, attempt, and
statement/program step there. Do not store those labels in `river-tx` records.
Any correlation-map overflow, missing mapping, reused attempt ID, unmatched
outcome, or duplicate terminal outcome invalidates the diagnostic run.
Correlation entries may be retired only after the server outcome and client
disposition have been matched and the reconciled aggregate counters updated.

An edge is valid when it corresponds to a grant precondition actually enforced
by `LockExactScheduler`; it does not have to represent incompatible lock modes.
The scheduler processes conversions first and grants ordinary requests only
from the queue head. A compatible reader can therefore depend on an
incompatible FIFO predecessor and legitimately participate in the wait-for
cycle. Cycle diagnostics must preserve that distinction rather than reducing
every edge to a mode-conflict test.

Do not log every lock operation. The normal hot path remains bounded and
allocation-stable; detailed records are admitted only in an explicit diagnostic
run.

Current lock metrics need two corrections before they can support conclusions:

1. `server_metrics_scope=server_lifetime` must be supplemented by
   measured-phase deltas.
2. `lock_waits_entered` currently increments before scheduling, including
   requests that can be granted immediately. Add actual-blocked count and
   blocked nanoseconds rather than calling every admission a wait.

Also expose deadlock victim selections separately from individual queued
requests terminated with `DEADLOCK`. Reconcile the former to victim transaction
outcomes and the latter to cleanup work; they are different cardinalities.

### Deterministic reproducer

Build a focused concurrency test around warehouse 1:

1. Start one Payment transaction and one New Order transaction with declared
   isolation levels.
2. Use test-owned barriers at relevant lock boundaries to force each possible
   interleaving.
3. Capture predicate/tuple/base-row acquisition and queue dependencies.
4. If a cycle forms, prove that every edge corresponds to a scheduler grant
   precondition that actually prevents progress. Record request mode, held mode
   where applicable, queue kind/order, and the evaluated grant predicate for
   each edge, then select the expected deterministic victim.
5. Prove complete cancellation, rollback, and immediate reuse of the victim
   session.
6. Repeat RR/SERIALIZABLE and SERIALIZABLE/SERIALIZABLE combinations.
7. Add a three-terminal case because the throughput run shows a sharp change
   between two and three terminals.

The test must use the real SQL, relational, transaction, and lock paths. A
synthetic lock-manager test is useful only as a lower-level companion.

### Isolation contract required for P0 exit

Retain the current `REPEATABLE_READ` JDBC/`SERIALIZABLE` program combination as
an explicitly labelled mixed-isolation reproducer. It is required diagnostic
evidence because it exposes the observed failure, but it is not a valid
benchmark contract and cannot satisfy P0.

Before P0 exits, choose and document one effective benchmark isolation level
for all JDBC and transaction-program attempts. The report must print the
declared contract and the effective level observed on each execution path;
startup or preflight fails on any mismatch. Then run both the 50/50 New
Order/Payment discriminator and the standard transaction mix at two, three,
four, and ten terminals under that same contract, durability mode, seed, and
database image.

The choice must preserve the declared transaction semantics. Two acceptable
implementation routes are:

- correct the lock defect and run the JDBC baseline at `SERIALIZABLE`, matching
  the current program behavior; or
- expose program isolation as a real execution input and select another common
  level only after its TPC-C and River isolation semantics are reviewed and
  tested.

Silently weakening program isolation, retaining path-specific defaults, or
changing isolation merely until the retry count falls does not satisfy P0.

### Decision tree

| Evidence | Required response |
| --- | --- |
| The recorded back-edge does not close a cycle of scheduler-enforced grant preconditions | Fix deadlock detection before performance work |
| A graph edge has no grant precondition enforced by the scheduler | Fix deadlock detection or blocker-graph modelling |
| A valid FIFO or conversion-priority edge produces pathological cycles | Analyse fairness, starvation, and liveness; change scheduler policy only with proofs for both cycle resolution and starvation bounds |
| Transactions acquire the same logical resources in opposite orders | Establish and enforce one canonical order |
| The cycle occurs only under the current mixed-isolation reproducer | Record that diagnosis, then still select and validate one common benchmark contract before P0 exit |
| A victim retains a holding or queued request | Fix cancellation and cleanup lifecycle |
| A valid waiter times out after blockers complete | Fix wakeup/fairness/liveness |

### P0 acceptance gate

The P0 evidence bundle must include the correlated mixed-isolation reproducer,
the selected common isolation contract and its semantic rationale, and the
required 50/50 discriminator and standard-mix sweeps under that contract. For
each two-, three-, four-, and ten-terminal run in both sweeps, both parts of the
gate must pass.

#### Correctness

- requested and effective JDBC/program isolation both equal the declared
  benchmark contract;
- every victim selection passes cycle self-validation: its graph closes and
  every edge corresponds to a grant precondition enforced by the scheduler;
  there are no false detector cycles;
- every selected victim completes rollback and cancellation, retains no lock
  holding or queued request, and its session is immediately reusable;
- zero lock timeouts;
- zero waiter or conversion liveness failures after its scheduler grant
  preconditions become true;
- zero retry-exhausted transactions;
- zero fingerprint-table or correlation-map overflows;
- every victim selection is included in exactly one epoch/fingerprint counter,
  and fingerprint counts reconcile to the phase total;
- victim selections, victim transaction outcomes, queued-request
  cancellations, and client retries satisfy the attempt-correlation rules;
- every retry has one exact, valid server `StatusCode` classification and
  reconciles to one server transaction outcome; there are no unknown, merged,
  duplicated, or unexplained retry classifications;
- every predeclared target cycle fingerprint is absent or within its declared
  bound, and no other fingerprint, lock timeout, retry exhaustion, resource
  exhaustion, cancellation failure, or indeterminate outcome has increased
  without an accepted causal explanation;
- each observed lock block is classified by resource scope, requested and held
  mode, queue relationship, and the scheduler grant predicate that prevented
  progress; aggregate wait, victim, timeout, and cancellation counts remain
  distinct;
- all post-run business and checkpoint invariants pass.

Correctness is absolute for these diagnostic runs. A favorable aggregate retry
rate cannot compensate for one false cycle, cleanup leak, unexplained retry, or
timeout/liveness failure.

#### Failure-mode displacement check

For each P0 correction, predeclare the target fingerprint or outcome class and
compare the same phase, transaction mix, isolation, terminal count, seed,
durability, database image, and source/configuration fingerprints before and
after the change. Normalize every failure-mode count by transaction attempts as
well as reporting its absolute count. The correction passes this check only
when:

- the target mode is zero or within its explicit bound;
- no existing non-target fingerprint or outcome class regresses beyond the
  predeclared noise bound;
- every novel fingerprint or outcome class is reconstructed and explained,
  rather than hidden in an aggregate `retries` or `errors` total;
- client attempts, server transaction outcomes, victim selections, cancelled
  requests, and retry dispositions reconcile exactly for each benchmark phase;
- post-run active transactions, held locks, queued requests, retained snapshots,
  and bounded-diagnostic overflow counts are all zero.

If the target disappears but a replacement mode grows, the result has located
the next blocker; it has not passed the gate. Preserve that result as diagnostic
evidence, revise the hypothesis, and do not use an improved TPS number to waive
the failed correctness condition.

#### Performance regression guard

- zero New Order retries attributed to `warehouse-read`;
- total retry rate is no worse than 5% of committed transactions, consistent
  with the approximately 2.7% New Order retry rate seen in the New
  Order-dominant diagnostic;
- retry rate remains within that bound at every terminal count rather than only
  in the aggregate;
- committed TPS does not regress from two through ten terminals beyond a
  predeclared measurement-noise tolerance, and added concurrency no longer
  turns primarily into retries.

If either part of this gate fails, no P1-P3 production optimization and no P4
promotion begins. Low-cost bounded P1 telemetry and single-terminal durability
baselines may proceed in parallel when they do not alter transaction behavior
or delay P0 diagnosis.

### Timebox

Timebox the first P0 pass to one working day:

- up to half a day for mix controls, phase-scoped status accounting,
  transaction-attempt correlation, and bounded fingerprint/exemplar capture;
- up to half a day for the deterministic reproducer and first cycle analysis.

At the end of the day there must be either a demonstrated cycle with exact
resource order, a demonstrated detector/scheduler defect, or a clearly named
missing observation. There should not be a speculative production rewrite.

## P1: durability-path attribution and group-commit ceiling

P1 production optimization begins only after P0 passes. Low-cost bounded
telemetry and single-terminal durability baselines may be collected while P0 is
in progress, but they do not authorize coordinator tuning. The existing
group-commit counters are insufficient for a coordinator decision and must not
be exposed as if they describe one homogeneous path:

- `cohortCount` records every drained writer batch, including non-groupable
  singleton work;
- `directFallbacks` combines materially different direct paths and cannot show
  whether work was initially ineligible or failed before a group decision;
- `forceCount` records only the shared-group force; a direct commit may force
  WAL without appearing in that count;
- group eligibility can be denied by active scans, absent supported hybrid
  work, tuple lifecycle activity, lock conflict, or serializable cursor use.

P1 must first instrument a measured-phase durability funnel. Keep counters
bounded and allocation-stable, and classify a transaction once at each state
transition rather than inferring its path after completion.

### Eligibility and cohort funnel

Report:

- total commit submissions, split into read-only, write, and failed-before-
  submission outcomes;
- groupable admissions and initially ineligible admissions separately;
- each eligibility predicate and a stable primary ineligibility reason so the
  primary-reason counters reconcile exactly with total ineligible admissions;
- attempted group cohorts and transactions admitted to them;
- successful group cohorts and successfully published group transactions;
- successful-group cohort-size histogram, average, and maximum;
- group failures by stage and exact `StatusCode`;
- direct commits by mutually exclusive reason: initially ineligible,
  explicit direct path, and any other real caller discovered during
  implementation. Do not retain a reason for a path that no longer exists. A
  groupable transaction that fails
  preflight or admission is aborted with that exact group failure; it is never
  re-executed as a direct commit.

If several eligibility predicates are false, retain a predicate bitmask or
per-predicate counters for diagnosis, but also select one documented primary
reason. Do not allow overlapping reason counters to masquerade as a funnel.

The metrics must distinguish the eligibility of each submitted transaction
from the disposition of the drained writer batch. An ineligible batch head must
not cause otherwise-groupable followers to be counted as initially ineligible.

### WAL force attribution

Instrument the shared WAL force boundary, not only
`IndexedGroupCommitBatch.force`. For every force, report count, bytes covered,
elapsed time, status, and measured phase, tagged by mutually exclusive cause:

- shared group commit;
- direct commit;
- checkpoint;
- recovery or maintenance;
- another explicitly named caller.

The cause totals must reconcile with total WAL forces. File and directory
forces outside the WAL should be reported separately so checkpoint metadata
durability is not confused with transaction WAL force cost.

### Stage timing

Record count, total nanoseconds, and a bounded latency histogram separately for:

- queue residence, from submission until writer selection;
- group preflight;
- commit-group admission;
- WAL append;
- WAL force;
- transaction publication;
- direct-commit append, force, and publication where those stages differ;
- completion notification to the waiting session.

Wall-clock stage timings may overlap across transactions in a shared cohort.
Report cohort wall time separately from transaction-attributed wait time and do
not sum overlapping per-transaction durations as server elapsed time.

The current `preflight` bucket is still too broad to select a fix. Split it
into reclaim, mutation compilation, logical-row admission, operation-version
reservation, page freeze, WAL planning/reservation, and final conflict
validation. These remain bounded counters; diagnostic spans or event records
are sampled exemplars rather than an event per commit.

### Selected prepared-commit remediation

If the sub-stage accounting confirms the current preflight cost, replace the
single serialized prepare-and-publish operation with these ownership phases:

1. **Session preparation.** Seal and validate an immutable logical mutation
   description in session-owned, chunked, resource-accounted storage. Compute
   result admission and long-valued WAL/resource demand before enqueueing. This
   phase must not assign a commit sequence, alter shared pages, append a durable
   decision, or publish data.
2. **Cohort reservation.** The commit writer drains compatible prepared
   requests, assigns commit sequences, performs the final conflict check, and
   reserves the admitted page/version/WAL resources. A failure here aborts
   every affected request without a committed decision.
3. **Append and force.** Encode directly into provider-owned WAL reservations,
   append all admitted decisions, and issue one durable force for the cohort.
   Data-page force is not part of an ordinary transaction commit; foreground
   data-file writes and forces must be reported separately if they occur.
4. **Publication and release.** Publish each decision exactly once, acknowledge
   only after the required WAL durability point, release locks and transaction
   resources, and defer eligible dirty-page flushing to resource-accounted
   background work with explicit backpressure.

The logical prepared representation owns values until publication or abort;
the WAL reservation owns encoded bytes after append; shared pages become
visible only during publication. Pre-commit result-size admission remains
mandatory. An append/force outcome that cannot be determined fences the
session/store according to the existing indeterminate-commit contract rather
than retrying blindly.

`LocalWal.MAX_PENDING_RECORDS=16` is an implementation-sized array bound, not
an accepted scale contract. Do not raise it to another guessed constant. Before
P1 promotion, replace that coupling with admitted/chunked reservation storage
whose limit follows the configured WAL memory budget and addressability. A
request that cannot be admitted must receive an explicit backpressure or
resource status before append; splitting work must preserve one commit decision
and one force/publication outcome for the transaction or cohort.

Do not tune `MAXIMUM_ADAPTIVE_COALESCING_NANOS` merely because current cohorts
contain one transaction. First prove that eligible prepared requests overlap
at the queue. Once they do, choose a bounded wait from measured force latency
and response-time limits, and report the resulting cohort-size distribution.

### P1 measurement run

Run non-instrumented terminal sweeps with the bounded counters enabled and
report:

- committed and attempted TPS;
- server CPU utilization and blocked commit time;
- group eligibility ratio;
- successful-group admission ratio;
- successful-group cohort-size distribution;
- direct-commit distribution by reason;
- total force count, bytes, time, and latency by cause;
- queue, preflight, admission, append, force, publication, and notification
  time by path.

### Decision gate

- If group eligibility is low, do not tune coalescing from aggregate cohort
  size. Determine which eligibility reason dominates and whether changing it is
  semantically valid.
- If eligibility is high but group success is low, address the measured
  preflight/admission failure before tuning queue timing.
- If eligibility and group success are high, successful cohorts remain near
  one despite concurrent queue depth, and shared-group force dominates commit
  time, coordinator/coalescing work is justified.
- If direct-commit forces dominate, investigate direct-path eligibility and
  force behavior; shared-group `forceCount` is not the relevant denominator.
- If checkpoint, recovery, maintenance, or non-WAL forces dominate, isolate
  that cause rather than changing transaction group commit.
- If cohorts are healthy and force time is a small fraction of service time, do
  not tune group commit.
- If one terminal remains limited by direct or shared force latency, report
  that exact durability path as a denominator rather than attributing it to
  JDBC chatter.

### 2026-09-03 P0 matrix and first reconciled durability funnel

The canonical-ordering source fingerprint
`d62bbab8108840f73a55492cf75fe282198c3381b85005fa012ce4e3d6ef9f82`
completed the required serializable diagnostic matrix with zero client
retries, zero server retryable outcomes, zero unclassified outcomes, zero
correlation overflows, and passing post-run invariants:

| Mix | Terminals | Committed TPS | Correctness outcome |
| --- | ---: | ---: | --- |
| New Order/Delivery 50/50 | 2 | 63.2 | passed |
| New Order/Delivery 50/50 | 3 | 64.0 | passed |
| New Order/Delivery 50/50 | 4 | 67.2 | passed |
| New Order/Delivery 50/50 | 10 | 61.4 | passed |
| Standard | 2 | 57.8 | passed |
| Standard | 3 | 34.2 | passed, performance outlier retained |
| Standard | 3 | 62.5 | passed, exact-configuration repeat |
| Standard | 4 | 58.4 | passed |
| Standard | 10 | 54.7 | passed |

The matrix passes the absolute P0 correctness criteria for the observed runs.
It does not turn the throughput samples into a scaling claim. In particular,
the retained 34.2 TPS sample and its 62.5 TPS exact repeat demonstrate host
variability too large to waive with an invented percentage. A P0 performance-
regression conclusion requires repeated, matched samples and a stated
statistical comparison; the single-sample values above are diagnostic
coordinates, not an arbitrary promotion threshold.

The first fully reconciled commit/WAL diagnostic run was:

```sh
tools/tps-test.sh --mix=standard --terminals=2 --measured-seconds=3 \
  --output-dir=/private/tmp/river-commit-funnel-v2 --seed=42
```

It completed at 55.3 engineering TPS with zero retries and failures. Its
server-lifetime commit funnel reconciled exactly:

- 674 submissions = 419 writes + 255 read-only + 0 failed before submission;
- 419 writes = 201 groupable + 218 initially ineligible;
- all 201 groupable writes entered and successfully published through 201
  cohorts, so average and maximum cohort size were both 1.0/1;
- all 218 ineligible writes committed directly: 194 had only the serializable-
  cursor predicate, 12 had only tuple-lifecycle work, and 12 had both;
- 425 WAL forces = 201 shared-group + 218 direct-commit + 2 checkpoint + 3
  recovery/maintenance + 1 other, with every force reporting `OK`;
- shared-group forces consumed 778,470,751 ns for 2,685,271 bytes; direct
  forces consumed 825,924,281 ns for 1,736,006 bytes;
- shared-group preflight recorded 4,341,959,917 ns across 201 cohorts and
  direct preflight 1,381,907,380 ns across 218 transactions.

These are server-lifetime counters, so load, preflight, warmup, measured work,
drain, and checkpoint are not yet separable. The matching count between the
201 singleton groups and load activity is a hypothesis, not phase attribution.
Measured-phase snapshots or generic transaction-epoch aggregation remain
required before a throughput denominator can use these totals.

The decision supported now is narrower and important: do not tune coordinator
coalescing from the cohort-size-one aggregate. First prove whether the dominant
serializable-scan exclusion is a necessary semantic restriction. If it is not,
remove it with strict-2PL phantom/write-skew, atomic publication, force-failure,
cleanup, and recovery tests. Then remeasure eligibility and real runtime cohort
formation. Tuple-lifecycle grouping remains a separate mechanism and must not
be enabled by weakening its preflight admission check.

A later ten-terminal diagnostic at workspace fingerprint
`1df02974bfe7a2e7dcb9e036ca46aeaa0b542ac283124cb6a75caa9c4eebc81c`
further
separated queue formation from writer cost. It recorded 1,283 groupable
transactions in 1,264 successful cohorts: 1,245 singleton cohorts and 19
two-transaction cohorts, an exact mean of 1.015 rather than evidence of useful
grouping. There were no group failures or fallbacks. In the same server-
lifetime interval, 1,438 lock blocks accumulated 95.1 seconds of blocked time,
or 66.1 milliseconds per observed block. With one warehouse and strict 2PL,
many contenders therefore remained upstream of the commit queue and could not
be made group-ready by a longer coordinator wait.

The successful-cohort writer stages averaged approximately 4.93 milliseconds:
0.655 ms preflight, 0.109 ms append, 3.650 ms force, and 0.508 ms publication.
Within preflight, physical compilation averaged 0.463 ms per transaction, WAL
planning 0.047 ms, and page freeze 0.054 ms. Publication currently combines
physical installation, visible-frontier publication, WAL-view release,
transaction completion, and lock-scheduler release; split those before
selecting its fix. These are diagnostic server-lifetime averages from a dirty
workspace, not current-source or measured-phase throughput evidence.

A retained current-source diagnostic with fixed seed 42 confirms the same
shape rather than a new throughput claim. The ten-terminal standard mix
completed at 114.7 engineering TPS with zero retries, failures, timeouts, or
cleanup residue and 9.0 JDBC statement requests per counted commit. The server
published 1,295 groupable transactions in 1,270 successful cohorts: 1,246
singletons, 23 pairs, and one size-three cohort (1.0 transactions/cohort to one
decimal place). Shared force consumed 4.80 seconds across the server lifetime,
while 1,543 actual lock blocks accumulated 94.37 seconds. This continues to
show both an upstream lock-service constraint and predominantly singleton WAL
forces; it does not justify attributing a 114.7-versus-earlier-sample delta to
the changes. The artifact is `/private/tmp/river-tps-current-v3`; metadata
records stable workspace fingerprint
`03eec4da421c2959a12f9063fe687c9c94c32a868102a17ce81111497f92165f`.

This trace changes the P1 implementation boundary. Move only pure logical
sealing, result admission, long-valued sizing, and resource-budget admission
before enqueue. Keep commit-sequence assignment, reclamation, cumulative
physical compilation, page freeze, final conflict validation, WAL reservation,
force, and publication under the canonical writer initially. Distinct logical
keys may share a physical page, so independently prepared full-page images can
lose one another's updates without generation validation and deterministic
delta merge/rebase. Moving the current physical compiler wholesale is not a
safe optimization.

The smallest scalable P1 slice is therefore:

1. seal one immutable, chunked logical commit description and retain its result
   and resource leases until publication or abort;
2. admit queue work against configured memory, active-transaction, staging,
   and WAL budgets, distinguishing cancellable transient pressure from an
   impossible request rejected before side effects;
3. form cohorts by cumulative admitted byte/page/version demand rather than a
   fixed transaction or WAL-record count;
4. retain one cumulative physical writer shared by group and direct policy;
5. reserve and append WAL through budget-derived chunks, ending a large
   logical decision with one durability/publication outcome; and
6. install forced generations invisibly, advance the visible frontier once,
   release locks, notify sessions, and release leases.

Do not create a second prepared executor. The direct and group policies must
share technical owners for logical preparation/admission, canonical physical
staging, WAL append/force, post-force publication/fencing, and terminal lock
and resource cleanup.

### 2026-09-04 P1 resource-ownership and current-source checkpoint

The P1 implementation now treats memory admission as ownership rather than as
anonymous counter increments. One compiled database resource plan is the
authority for page-cache geometry, lock-provider bytes, WAL demand, staging,
and a byte-derived chunked version workspace. There is no separately guessed
version-operation count. The indexed store holds an authenticated per-open
receipt tied to its provider lease, database incarnation, and WAL generation;
sessions can be created only through a database-bound context that verifies the
manager, table, vacuum, optional group coordinator, governor, and registry
ownership set.

Session and SQL-runtime retained memory use authenticated absolute high-water
receipts. Repeating admission after an allocation failure is therefore
idempotent instead of charging the same growth twice. Provider release is
rejected while a store, transaction, waiting ticket, or retained component is
live. WAL-plan array growth allocates every replacement array before publishing
any of them, so an allocation failure cannot leave a partially enlarged plan.
Focused tests cover cross-governor claim races, forged store-release attempts,
component-exact release, provider close fencing, repeated high-water admission,
mixed session ownership, group-preflight failure cleanup, and store reopen.

The exact no-argument command `tools/tps-test.sh` then completed successfully
at source state measured on 2026-09-04. It built only because affected classes
had changed (`clean=false`), started and stopped an ephemeral managed server,
passed load/preflight/checkpoint invariants, and reported:

- 1,303 measured commits and 130.3 engineering TPS;
- zero retries, errors, timeouts, unclassified outcomes, or cleanup residue;
- 1,223 measured-window groupable write admissions and 1,223 successful group
  transactions in 1,221 cohorts;
- 1,219 singleton cohorts and two size-two cohorts, for 1.0 transactions per
  successful cohort to one decimal place;
- 1,221 shared-group WAL forces covering 10,589,996 bytes and consuming 4.630
  seconds;
- 1,642 actual measured-window lock blocks consuming 85.513 seconds; and
- 8.9 JDBC statement requests per measured commit and 8.7 per attempt.

This is a diagnostic checkpoint, not a 50x or MariaDB-parity claim. It confirms
that the prior retry storm is absent in this run and that the current ceiling is
still dominated by upstream lock residence and nearly one force per write.
Only two requests overlapped enough to form size-two cohorts, so increasing an
arbitrary coalescing delay is still not justified. The next optimization must
either shorten the lock-held path to the commit queue or remove a measured
force/service bottleneck while preserving the now-explicit ownership and
cleanup contracts.

The full `:river-engine:test` invocation was stopped after an unrelated
external-sort stress test spent several minutes actively checksumming and
merging 65 disk-backed runs. The JVM thread dump showed CPU progress rather
than a deadlock or leaked commit thread. Focused affected tests passed; the slow
sort case remains separate performance work and is not represented as full
suite evidence.

### 2026-09-04 P1 force and publication discriminators

Two consecutive exact no-argument `tools/tps-test.sh` runs completed at 132.4
and 131.4 engineering TPS. The second reported
`build=skipped reason=compiled_classes_current`. Both had zero retries, errors,
timeouts, unexplained outcomes, and cleanup residue, and both passed load,
preflight, checkpoint, deadlock-reconciliation, and performance-capture gates.
They reported 8.9 and 8.8 JDBC requests per measured commit respectively. This
is the required tool-operability evidence, not a throughput promotion claim.

A retained one-terminal durability baseline at
`/private/tmp/river-p1-one-terminal-current` completed at 135.7 engineering TPS
with zero measured lock waits. Its 1,255 measured write commits formed 1,255
singleton cohorts and issued 1,255 shared-group WAL forces. Those forces consumed
4.752 seconds of the ten-second measured window. Ten terminals therefore did
not outperform one terminal materially, and the one-terminal result proves
that lock contention is not required for the current force-per-write ceiling.

A separate ten-warehouse/ten-terminal discriminator at
`/private/tmp/river-p1-ten-warehouse-current` completed at 122.0 engineering
TPS. It recorded 582 measured writes in 581 successful cohorts, only one
size-two cohort, plus 850 actual lock blocks consuming 42.223 seconds during a
five-second measured window. Distributing terminal homes across warehouses did
not create useful commit-queue overlap. This rules out the single hot warehouse
as the complete explanation; broad retained lock ownership and eager
force-per-arrival behavior are separate P1 constraints. The short run is a
mechanism discriminator, not a warehouse-scaling claim.

Publication telemetry now preserves the existing aggregate while splitting it
into forced-generation preparation, page/frontier installation under the
snapshot barrier, and transaction completion/lock release. It uses the existing
reusable commit batch as the timed participant, adds no executor, allocation,
or transaction-state branch, and records exact sub-stage failures. In the
retained default run at `/private/tmp/river-p1-publication-split-current`, 1,242
cohorts consumed:

- 519,875,721 ns in aggregate publication;
- 17,770,166 ns preparing forced publication;
- 2,610,341 ns installing pages and the visible frontier; and
- 498,393,115 ns completing transactions and releasing locks.

Transaction completion therefore represented 95.9% of measured publication,
about 0.40 ms per cohort; page/frontier installation was about 0.002 ms per
cohort. Shared WAL force remained the larger serialized stage at 4.571 seconds.
The run completed at 132.8 engineering TPS with zero retries or errors.

One narrow follow-up batched scheduler draining across a transaction's complete
lock release. It did not improve the completion denominator: 502,167,722 ns
across 1,263 cohorts, again about 0.40 ms each. Its 135.0 TPS result was within
the observed run noise. The candidate was removed rather than retained as an
unproved fast path. This falsifies repeated scheduler drain as the dominant
completion cost; resource/index unlinking, interval bookkeeping, and wake work
inside canonical lock cleanup remain to be separated before optimization.

The completion split then localized 491,033,433 ns of 494,440,767 ns of
transaction completion to canonical lock release; active-snapshot removal and
outcome publication consumed only 454,322 ns and 541,232 ns respectively. A
deeper retained run at `/private/tmp/river-p1-lock-release-split-current`
localized 486,244,016 ns of 491,673,158 ns of lock release to holding removal;
lock-outcome transition, queued-request cancellation, and record recycling were
negligible by comparison. These are aggregate cohort clocks, not a clock in
each lock-release iteration.

Holding counts now provide the missing denominator without retaining a sample
or imposing a diagnostic cap. Two further literal no-argument runs completed
at 131.1 and 133.3 engineering TPS; the second again reported
`build=skipped reason=compiled_classes_current`. They had zero retries and
errors, valid captures, successful checkpoints and reconciliation, and empty
terminal cleanup. The runs released 96,846 holdings for 1,231 writes and
98,575 holdings for 1,248 writes: 78.7 and 79.0 holdings per write. The latter
run split the holdings into 46,746 `KEY`, 1,248 `RANGE`, 32,886 `TUPLE_KEY`,
and 17,695 `TUPLE_RANGE` holdings. It also spent 4.681 seconds in 1,244 force
cohorts averaging 1.0 transaction and 506,834,637 ns releasing locks. The
first run was materially the same. This confirms a broad lock-footprint cost;
it does not justify deleting a lock class or weakening serializable semantics.

The next lock slice must explain why each workload step needs each physical
scope, then remove only duplicate or unnecessarily retained protection under a
single canonical serializable-locking policy. Scope totals alone cannot prove
redundancy. In parallel, the force denominator remains independently proven:
the coordinator is still receiving almost exclusively singleton writers and
forcing each cohort. The durability-overlap design below is therefore still
required even if lock-release cost is reduced.

Two retained single-terminal family probes separate the footprint without
adding TPC-C types to the transaction layer. New Order at
`/private/tmp/river-p1-lock-footprint-new-order` released 43,098 holdings for
336 writes, or 128.3 per write: 18,510 `KEY`, 336 `RANGE`, 16,168
`TUPLE_KEY`, and 8,084 `TUPLE_RANGE`. Holding release consumed 91,660,124 ns,
while force consumed 1,297,883,331 ns. Payment at
`/private/tmp/river-p1-lock-footprint-payment` released 11,295 holdings for 509
writes, or 22.2 per write: 6,411 `KEY`, 509 `RANGE`, 2,545 `TUPLE_KEY`, and
1,830 `TUPLE_RANGE`. Holding release consumed 18,867,375 ns, while force
consumed 1,969,788,657 ns. Both completed with zero measured waits, retries,
or errors. New Order therefore owns most of the standard-mix release volume,
but the approximately 3.86 ms force per singleton write is the larger
uncontended denominator for both families.

The post-slice compact `slopmark` scan reports 159.532 for
`TransactionManager`, 100.832 for `IndexedGroupCommitTelemetry`, and 87.510 for
`LockExactTable`; `IndexedGroupCommitBatch` is below the reported top-50
boundary. The telemetry additions remain one aggregate commit-path
responsibility, but the score is now a stop signal for adding per-family or
formatting policy there. Further lock attribution belongs at the
benchmark/session resolution boundary, while transaction and lock layers keep
only opaque tags and structural scopes.

The next P1 slice must therefore preserve two distinct denominators. First,
attribute completion work to holding unlink/index removal, interval removal,
grant scheduling, active-snapshot removal, and outcome publication, preferably
with group-level clocks rather than one clock per released lock. Second, design
durability overlap explicitly: merely delaying the current writer cannot admit
transactions that are still blocked behind locks retained through force. Any
proposal to expose or release work before force must define a durable visibility
frontier and prevent a dependent read-only or write transaction from being
acknowledged ahead of the WAL it observed. Without that dependency contract,
early lock release is a durability violation rather than group commit.

### 2026-09-04 queue boundary and radix-directory discriminator

The original `QUEUE_RESIDENCE` implementation stopped its clock only after
group preflight and commit-group admission. It therefore assigned physical
compilation to the queue and could not support the coordinator decision above.
Writer selection now ends queue residence before `IndexedGroupCommitBatch`
starts preflight. In the retained one-terminal New Order run at
`/private/tmp/river-p1-queue-boundary-new-order`, corrected queue residence was
10,119,166 ns across 151 selections, approximately 67 microseconds each, while
preflight consumed 264,999,953 ns. The independent queue-nonempty clock reported
9,565,459 ns. The two measures now describe the same boundary.

A caller-owned flat-combining prototype then tested whether even that handoff
was worth removing. It was not retained. A ten-second caller-combiner run at
`/private/tmp/river-p1-caller-combiner-new-order-10s` completed at 86.7
engineering TPS, while the immediately restored dedicated-writer control at
`/private/tmp/river-p1-dedicated-writer-control-10s` completed at 82.0 TPS.
That single noisy pair is not evidence of a useful throughput change. The
caller path reduced the independent queue-nonempty interval from about 31.5 to
2.8 microseconds per selection, but force remained approximately 3.6 ms per
write. Independent concurrency review also found that a production flat
combiner would require a finite structural combining epoch, waiter baton,
interruption preservation, close wakeup, self-close protection, and explicit
partial-completion progress. The dedicated writer remains the simpler owner;
the prototype and its test were removed.

A separate storage discriminator rejected WAL-file preallocation or a
content-only force as the next fix on this host. One hundred 8 KiB write/force
iterations averaged 4,045.835 microseconds for a growing file with metadata
force, 4,100.320 microseconds for a preallocated file with metadata force, and
4,090.815 microseconds for a preallocated file with content-only force. This
does not justify a WAL extent or footer-format rewrite.

Server JFR instead identified a bounded representation cost in the lock
manager. The pre-change New Order recording at
`/private/tmp/river-p1-new-order-lock.server.jfr` contains 10,080 execution
samples. River lock stacks account for 2,830 samples (28.08%), terminal holding
release for 450 (4.46%), and `LockRadixDirectory.get` for 354 (3.51%). Of the
radix samples, 298 came from typed lock-store chunk access. Every chunk lookup
walked seven dependent radix nodes even while repeatedly accessing the same
256-entry structural chunk.

`LockRadixDirectory` now owns a one-entry last-ordinal/value locality cache.
It updates on `set`, represents a removed or absent entry as a cached miss, and
retains the full long-addressed radix path for every other ordinal. This is a
locality optimization, not a capacity limit; it allocates nothing and changes
no lock identity, interval overlap, fairness, lifecycle, or resource budget.
An initial typed-store-local implementation was moved into the directory owner
before acceptance so hash-index and AVL primitive stores share the same
technical policy.

The owner-correct post-change recording at
`/private/tmp/river-p1-radix-cache.server.jfr` contains 8,775 execution samples.
`LockRadixDirectory.get` fell to 75 samples (0.85%), terminal holding release to
167 (1.90%), and all River lock stacks to 1,143 (13.03%). The matched
instrumented runs completed at 77.8 and 99.8 engineering TPS respectively, but
that one pair remains supporting mechanism evidence rather than a throughput
claim. The complete `:river-tx:test` suite passes, including allocation,
512-lock pressure, interval/fairness, collision, and slot-reuse coverage; a new
test alternates low and distant ordinals and proves cache invalidation across
remove and reuse.

The post-change compact slopmark scan reports 83.899 for
`IndexedGroupCommitCoordinator`; `LockRadixDirectory` remains below the top-50
report boundary. The queue correction did not add a second coordinator policy,
and the locality mechanism was moved to its representation owner rather than
duplicated across typed and primitive callers.

Commit-writer fencing is also immediate at transaction admission now:
`IndexedTableStore.transactionAdmissionStatus()` checks the store admission
state before version-workspace pressure. Fault tests prove that an unexpected
writer exit or WAL force failure makes the next session begin return `FENCED`,
with no fabricated transaction outcome, rather than admitting work that can
only fail later.

Two subsequent literal no-argument `tools/tps-test.sh` runs reported
`build=skipped reason=compiled_classes_current`, completed at 75.5 and 77.2
engineering TPS, and had zero retries, errors, timeouts, unexplained outcomes,
or cleanup residue. Both passed load, preflight, checkpoint, deadlock
reconciliation, and performance-capture gates. Their absolute TPS is below
earlier same-source local samples and is not attributed to this change; the
host has exhibited large broad stage-time variation. The accepted evidence is
the direct sampled-method reduction and unchanged correctness outcomes.

The external harness currently supplies only a mature-system ceiling, not an
eligible pair. Its guarded one-worker sample New Order MariaDB run completed at
271.74 TPS with successful invariants, while the matched River setup was
rejected before workload admission because the harness's parameterized insert
and `SELECT ... FOR UPDATE` preflight received `INVALID_EXTERNAL_INPUT`. No
River zero-TPS value and no cross-engine ratio is reported. Re-run the pair only
after the independently developed harness comparison path declares this River
contract compatible.

The cache reduces a proved CPU tax but does not solve P1's force-per-write
ceiling. The same JFR attributes 11.82% of pre-change samples to physical group
preflight and 50.98% to transaction-program execution, while the measured
durability funnel still forces every singleton write. Further lock work should
target interval-resource representation only with matched profile evidence;
removing strict-serializable protection or releasing locks before the accepted
durability/visibility frontier remains out of scope.

## P2: paired protocol-collapse proof

Protocol collapse remains promising, but it must be tested one family at a
time on the same server commit and database image.

Payment is not currently a trivial adapter. Sixty percent of Payment inputs
select a customer by non-unique last name and choose the lower median. The
current implementation performs a count followed by an ordered row traversal.
Transaction-program `ROW_SET` is terminal and cannot provide a prior value to
later steps, and River SQL has no `OFFSET` support. Full Payment therefore
requires a reviewed representation for median selection; it must not silently
implement only the customer-ID branch.

Use this staged proof:

1. Verify that every Payment branch is representable without changing TPC-C
   semantics.
2. If necessary, build a diagnostic program for the customer-ID/good-credit
   branch only, labelled as a mechanism test.
3. Run interleaved JDBC/program A/B samples with identical isolation,
   durability, seed, data, and terminal count. Isolation must be the common
   contract selected and validated at P0, not a P2-specific override.
4. Require exactly one `EXECUTE_PROGRAM` request per program attempt.
5. Compare family latency, server CPU, allocation, bytes, and committed TPS—not
   request count alone.

Stop after the pilot if the predeclared improvement threshold is not met. Do
not implement Delivery and Order Status merely because Payment was planned.
Their branch, ordered-range, and result-shape semantics need independent
classification.

## P3: demonstrated server hot paths

JFR currently suggests buffer access, SQL value validation, tuple-key
validation/comparison, and UTF-8/result work. Those samples are hypothesis
generators only:

- the client recording was too sparse;
- the server recording included startup, load, preflight, warmup, measurement,
  and checkpoint;
- leaf samples were inspected without complete inclusive attribution;
- no changed mechanism was paired against a control.

After P0-P2, collect a measured-phase-only server profile long enough for
stable inclusive stacks. For each proposed optimization, require:

- a meaningful inclusive CPU or allocation share in the target workload;
- an isolated mechanism benchmark;
- a candidate/control result from the same commit;
- unchanged validation, ownership, and corruption boundaries;
- affected integration tests and allocation/copy counters.

### 2026-09-03 admitted mechanism checkpoints

Server-JFR checkpoints were taken while removing already demonstrated
hot work. They use the same short managed-server diagnostic command and include
load, preflight, warmup, measurement, drain, and checkpoint; they are mechanism
evidence, not stable measured-phase throughput samples or a promotion A/B.

The first checkpoint, whose folded stacks are at
`/private/tmp/river-capability-occupancy-jfr-v4/server.folded.txt`, contained
8,566 execution samples. Tuple mutation compilation accounted for 21.3 percent,
tuple insert for 19.0 percent, complete page validation for 16.9 percent, and
leaf mutation preparation for 16.0 percent. Insert occupancy itself fell below
the sample resolution after it was changed from a key-by-key scan to exact
arithmetic over a fully validated page header. Exact-fit and one-byte-over tests
cover ordinary and fence keys.

The page mutation path now shifts only the affected key and slot ranges in the
canonical 16 KiB page. External key bytes are stabilized in caller-owned tree
workspace before traversal so duplicate/sliced aliases cannot be corrupted by
copy-on-write mutation. A single-use mutation capability proves complete input
validation for the exact buffer, start, schema, shape, page type, generation,
and exclusive writable borrow. Canonical post-mutation sealing is distinct from
validation of externally supplied bytes. Foreign, reused, mismatched, and
nested-borrow capabilities fail, while randomized model tests validate every
successfully mutated page through the full codec.

The validation-lineage profile at
`/private/tmp/river-validation-lineage-jfr-v1/server.folded.txt` contained 7,048
samples. Complete leaf preparation fell from 16.0 to 0.1 percent, total page
validation from 16.9 to 1.2 percent, and tuple mutation compilation from 21.3
to 5.3 percent. In-place mutation itself was 0.6 percent. This validates the
removed mechanism; the run's reported TPS is deliberately not used as an
improvement claim.

That profile then exposed response text handling: protocol encoding accounted
for 11.5 percent of samples and UTF-8 decoding for 12.1 percent. Result carriers
already owned validated canonical UTF-8, but sizing and writing repeatedly
decoded each cell to UTF-16 and encoded it again. Command and row carriers now
expose byte length and direct copy over that same owned representation, and one
shared response-value encoder writes either carrier without a second executor
or value layout. The focused allocation and protocol codec tests pass.

The post-change profile is
`/private/tmp/river-protocol-utf8-jfr-v1/server.folded.txt` with 7,152 samples.
No sampled `Utf8TextDecoding.decode` stack is below
`ProtocolResponseTextEncoder`, all response encoding is 0.3 percent, and the
value/text helpers account for 0.1/less than 0.1 percent. The remaining UTF-8
decode samples belong to other execution paths. The slopmark stop-and-refactor
gate reduced `ProtocolResponseEncoder` from the failed intermediate 249.9 score
to 216.9; the extracted shared value encoder scores 18.6. The managed TPS run
completed every phase with valid invariants and zero workload retries or
errors. Its 79.1 TPS is one short diagnostic observation, not a performance
ratio or gate result.

The result encoder still makes a sizing traversal before its write traversal,
but at 0.3 percent inclusive share it is not the next target. A fused encoder is
not justified by the current evidence. Likewise, singleton and row-set
publication must share one typed-value ownership model rather than creating a
duplicate executor.

The same profile showed that lock release always entered scheduler overlap
search even when the global waiter count was zero. The scheduler now returns
before enqueue/search only for that exact empty-waiter state; request enqueue
continues through the existing unguarded grant/deadlock path. Cancellation with
no survivor and cancellation with a compatible surviving reader are tested
separately. Independent concurrency review found the counter is changed only
under the lock-manager monitor and covers ordinary plus conversion waiters. In
the post-change profile at
`/private/tmp/river-empty-waiter-jfr-v1/server.folded.txt`, the sampled
release-to-overlap-search chain was 2.0 percent, down from 2.4 percent in the
immediately preceding profile. That small mechanism is complete but is not a
large throughput claim.

That post-change profile also provided a stronger next admission: 469 of 5,424
samples (8.6 percent) reached `TupleKeyCodec.logicalRowId`, almost entirely
through complete key validation performed merely to read the final eight-byte
physical suffix. Tuple-page admission had already validated every slot, key,
descriptor value, ordering relation, and positive row ID. Leaf publication now
requires a caller-owned header bound to the exact validated `ByteBuffer`, start
offset, and leaf type. Envelope-only validation cannot issue that token;
provider reuse remains conditional on its page-generation/schema/descriptor
stamp; and mutation consumes the token before changing bytes. The read then
copies only the slot fields and row-ID suffix. The public self-validating key
operation remains for standalone untrusted keys, so this does not create a
second key representation or weaken an external validation boundary.

The first direct-suffix prototype at
`/private/tmp/river-validated-leaf-jfr-v1/server.folded.txt` removed the sampled
self-validating `logicalRowId` call from leaf publication, but independent
correctness review rejected its simple token. It could be minted without a
complete validation owner, was not reliably revoked by page release/reuse or
no-op mutation, did not bind a single-use mutation to the proof generation,
and allowed byte copying and proof copying to diverge. Its 106.8 diagnostic TPS
is not admitted performance evidence.

The replacement is an opaque, revocable validation proof. Complete page
validation alone can bind it after header, slot, key, order, row-ID, and slack
checks pass. The proof authenticates the exact buffer object, payload start,
schema, descriptor, page type, proof generation, provider reference, and page
generation. Restored read authority is a dependent lend revoked with its frame
generation; writable transfer is exclusive and consumed once. Mutation captures
the proof generation, invalidates authority before every success or no-op
outcome, and may reseal only if the captured authority is still current. Page
release revokes the reference only after release succeeds; a failed release
retains both borrow and proof for cleanup retry. Exact frame copying transfers
the format-defined payload and proof together, while an unauthenticated copy
copies bytes but clears authority. Proof generations never wrap: exhausting
the represented `long` generation permanently fences that proof with
`FENCED`.

`ByteBuffer` identity is intentionally an ownership capability rather than a
content hash. River-owned providers must not expose duplicate/sliced aliases
that mutate a read borrow, must keep a generation immovable while any borrow is
outstanding, and must reset authority before mutation or reuse. The production
frame owner supplies distinct non-aliasing targets for validated copies. These
are kernel ownership invariants, not checks repeated per leaf read.

The intermediate implementation pushed provider admission into
`TupleBTreePageSupport` and raised its slopmark score to 164. That was rejected;
admission moved to the dedicated `TupleBTreePageAdmission` owner, leaving
`TupleBTreePageSupport` at 0 and the admission class near 7.4. This is the
quality-gate outcome, not a performance result.

Full format and storage suites and focused engine ownership/SQL tests pass.
The former broad SQL allocation failures were traced to independently admitted
resident-run and spill workspaces, including incompatible retained shapes from
the prior statement. Sort admission now selects the greatest clean shape that
jointly fits both owners, charges their exact retained arrays, and replaces
inactive stale storage before either owner becomes active. Focused cold,
retained-shape, injected-allocation, failed page-reservation retry, spill, and
maximum-column statistics tests pass without raising a threshold or reducing
production cardinality. A full engine-suite repeat remains an integration
checkpoint rather than evidence for a throughput claim.

### Scale and admission contract

No P1-P3 implementation may introduce a convenient fixed row, byte,
transaction, cohort, queue, or concurrency cap. Storage and execution must
grow or stream against long-addressed structures and configured resource
budgets from the first production slice. A finite boundary is accepted only
when it follows from a wire/durable format, addressability, or an admitted
runtime budget, and it must define pre-side-effect rejection, backpressure or
spill/chunk behavior, and cleanup. A benchmark-sized constant is not evidence
for a production limit.

Structural format and algorithm boundaries remain valid. The fixed page size,
the slot count derived from payload/header/slot widths, signed page-ID tree
height, the two simultaneous borrows required by traversal/split ownership,
the page-image count derived from the 1 MiB durable WAL record, and logarithmic
latency buckets covering the full positive `long` range are examples. Each
must still fail explicitly at its representation boundary; proof and page
generations must not silently wrap.

The stored-row boundary now follows that rule. The former compiled 8,192-byte
SQL/session cap was unrelated to the physical format and rejected a valid
1,024-`BIGINT` row. It has been replaced by the exact single-row heap capacity:
`PageCodec.MAX_PAYLOAD_BYTES - HeapPage.HEADER_BYTES - HeapPage.SLOT_BYTES`
(16,216 bytes for the current fixed 16 KiB page). Exact-boundary encoding and
catalog publication pass, one byte over is rejected before publication, and
the 1,024-column statistics/reopen path passes. This does not promise overflow
rows; changing that storage contract would require a separate format design.

The implementation did not initially satisfy this contract. The 2026-09-04 P1
scale pass has implemented the following removals rather than raising the
constants; final integration and workload evidence is still pending:

- the 16-record local-WAL pending array and fixed transaction/cohort-derived WAL
  buffers, replaced by admitted chunked retention;
- the 1,024-operation and 127-changed-page execution ceilings, replaced by
  long-valued sizing and paged/chunked state up to format, Java addressability,
  or configured resource admission;
- 52-bit packed benchmark attempt IDs, short attempt fields, and the bounded
  retry-event ring, replaced by long-valued monotonic correlation and aggregate
  reconciliation for every event;
- the one-millisecond coalescing clamp and silently saturating commit, WAL, and
  benchmark counters; configured timing policy remains explicit and counter
  overflow now invalidates evidence;
- fixed benchmark warehouse, terminal, batch, retry-delay, and artifact sizes;
  artifacts stream through staged files and retry delay arithmetic saturates at
  the configured duration and deadline;
- the 64-row INSERT parser/executor coupling and fixed descriptor receipt/key
  workspaces; parsing grows to SQL/Java addressability, descriptor retention is
  budget-admitted, identity rows are consumed incrementally, and scan-mutation
  keys use session-budgeted structural pages; and
- the 1,024-connection server rejection, 64-statement and three-savepoint JDBC
  registries, 128-character metadata patterns, 16 metadata type filters, and
  32,767-relation metadata materialization ceilings. These now grow to Java
  addressability or return resource exhaustion only on real checked allocation
  failure; schema identifier and wire-format bounds remain unchanged.

The following are still P1 promotion blockers and must not be replaced by
larger constants:

- compile indexed page-cache and staging geometry from explicit byte budgets,
  account the full maximum retained layout, and pass that same configuration
  through every create/reopen/checkpoint/replication path;
- remove the guessed automatic progress, owner, write-size, and lock-share
  policies from `DatabaseResourceEnvelope`; SQL materialization pages and
  durable indexed pages are different units;
- reserve cumulative cohort page/version/WAL demand before physical staging,
  then split or apply cancellable backpressure when a set of individually
  admitted transactions cannot form one cohort; and
- seal prepared logical work with mutation generations and authenticated
  resource receipts, then assert that physical WAL bytes, write entries,
  staged pages, and version operations do not exceed the sealed demand;
- make every cancellation, fence, partial append/force/publication failure, and
  unexpected writer exit release prepared pages, operation versions, WAL state,
  transaction leases, and locks exactly once before notification; and
- move retained deadlock exemplars from fixed event counts to an explicit byte
  budget, and prove that disabled detailed capture allocates no records and
  reads no clocks while every victim remains covered by aggregate fingerprint
  and client-attempt reconciliation.

The independent P1 concurrency review also found an eight-level B-tree
traversal/path cap duplicated across validation, lookup, mutation, and vacuum.
That is not a proved format limit. It must be replaced by one bound derived from
positive page-ID addressability and B-tree progress, with synthetic trees above
depth eight accepted and cycles/depth overflow rejected as corruption.

Use caller-admitted byte budgets, chunked/paged retention, long-valued totals,
cancellable backpressure, explicit overflow evidence, and streaming artifacts.
The commit queue must be admitted against the already-owned active-transaction
budget rather than left unbounded. Diagnostics should be off by default and
enabled with an explicit byte budget; aggregate counters must still account for
every event even when exemplar retention is sampled.

### Evidence gates for remaining TPC-C techniques

The remaining recommendations in `tcpc-perf-notes.md` enter P3 through these
specific gates:

- **Buffer pool and hot-page residency:** record logical hits, physical reads,
  evictions by reason, dirty-page age, foreground stalls, and scan-caused hot
  eviction. Change sizing or replacement only if misses or eviction stalls are
  material. Any configured memory budget must account for an embedded host,
  server process, WAL, sessions, execution arenas, lock state, and OS cache;
  print the admitted budget at non-quiet startup.
- **Indexes and access paths:** for each TPC-C statement, record chosen access
  path, rows examined, index/data pages touched, and whether a sort was needed.
  For each maintained index, record mutation count, split count/time, copied
  bytes, and latch/lock wait. Remove or add an index only with a family-level
  read/write A/B and unchanged schema semantics.
- **Insert/update and page layout:** admit work only when New Order profiles
  show material heap allocation, row movement, B-tree split, fragmentation,
  or copy cost. Preserve recovery and page-format validation while changing
  layout directly; River has no released legacy format to retain.
- **Delivery:** after P0 ordering passes, compare the existing per-district
  statement path with a semantically equivalent prepared/set-oriented path.
  Preserve one atomic transaction, oldest-undelivered-order selection for each
  district, ascending district treatment, customer balance effects, and
  required response behavior.
- **Worker/admission tuning:** sweep active terminals per warehouse and report
  throughput together with p50/p95/p99, run-queue time, lock wait, commit-queue
  depth, CPU utilization, and context switches. A lower concurrency cap is
  acceptable backpressure, but it is not evidence that a lock-order defect is
  fixed.
- **Checkpoint/background I/O:** report phase-tagged data writes, WAL forces,
  checkpoint duration, dirty backlog and foreground stalls. Introduce fuzzy or
  incremental checkpointing only if a real checkpoint overlaps or delays
  foreground service in the declared run.
- **Partitioning and locality:** partition lock tables, page metadata, queues,
  allocators, or statistics only when an inclusive CPU/latch profile names the
  shared structure. Warehouse partitioning and NUMA affinity require
  multi-warehouse or multi-socket evidence and cannot precede the single-
  warehouse lock/commit fixes.

Every admitted optimization gets a paired candidate/control measurement from
one workspace fingerprint and identical database image. A generic expectation
that a mature TPC-C engine normally uses a technique is not sufficient.

### 2026-09-04 apparent TPS regression investigation

The reported fall from the 120--130 TPS band to below 50 TPS was treated as a
regression until disproved. The archived no-argument controls immediately
before the suspect work were 135.6, 131.1, and 133.3 TPS. Reversing later
production edits without a clean build did not restore that band: diagnostic
runs remained between 69.4 and 89.0 TPS.

An isolated clean-build A/B then replayed each post-control production slice.
These ten-second runs are diagnostic samples, not a promotion claim:

| Clean source state | TPS |
| --- | ---: |
| restored control | 110.4, 110.9 |
| transaction admission fencing only | 109.4 |
| admission fencing plus caller-owned commit combination | 110.6 |
| queue-residence attribution only | 109.8 |
| lock radix lookup cache only | 117.8 |
| lock-wait scope/mode accounting only | 113.3 |
| all suspect production slices combined | 125.4 |

All focused correctness tests passed for the coherent states. Every workload
sample completed with zero failed or unknown outcomes, zero measured retries,
valid performance capture, and complete transaction/lock cleanup. Most
importantly, the combined candidate did not reproduce the collapse. The data
therefore rejects attributing the severe reduction to
`IndexedGroupCommitBatch`, `EmbeddedCommitDiagnostics`, admission fencing,
caller combination, queue attribution, the radix cache, or wait-class
accounting. The first two files had already been followed immediately by 131.1
and 133.3 TPS controls.

A clean rebuild of the shared restored source then produced 124.4 and 123.9
TPS. The exact stale runtime component from the earlier low runs was not
retained, so the investigation must not claim a specific stale class as the
proved cause. A class-origin audit found that class directories preceded
project jars and that sampled restored classes resolved from those directories.
It did identify an unsafe tooling contract: `tools/tps-test.sh` inferred
freshness from a timestamp marker and five sentinel classes, could not detect
deleted or backdated sources, and added same-module jars as fallback classpath
entries.

The tool now always invokes Gradle's incremental `:river-bench:classes` task,
never `clean`, and launches from Gradle-owned class and resource directories
without duplicate project-jar fallbacks. Gradle remains responsible for
content-based up-to-date decisions; a no-change run executes no compilation.
Two no-argument validations after this change reported 125.1 and 124.5 TPS,
with all 18 Gradle tasks up to date and the same correctness evidence described
above.

Future source attribution must use clean isolated controls when a result is
surprising, preserve output artifacts, and interleave candidate/control runs.
A short single sample may trigger investigation, but it cannot convict a code
change or establish an improvement.

## P4: normative capacity evidence

The Alpha3 gate is a promotion test, not a hypothesis-discovery loop. Run it
only after the mechanism gates pass. The normative requirement remains:

- publish scaling points at 1, 2, 4, and 8 warehouses and continue doubling
  warehouses until River sustains the target or reaches the admitted reference-
  host maximum; use ten terminals per warehouse unless a separately recorded
  terminal sweep establishes a better standards-valid mapping;
- at least five warmups and exactly ten measured samples at each reported
  qualifying point;
- at least 100,000 completed transactions per sample;
- the documented 95% confidence lower bound of at least 1,000 committed TPS;
- no retry-exhausted or failed transaction families;
- standard scale and the declared Alpha3 scheduling/durability environment;
- the identical semantic manifest against River, PostgreSQL, and MariaDB: same
  scale, seed, mix, terminal scheduling, requested isolation, acknowledged
  durability, warmup and measurement windows, and host;
- in addition to the absolute gate, River median committed TPS at least 80
  percent of MariaDB at the same qualifying point and River per-family p99 no
  more than 20 percent worse for every family with sufficient samples.

The benchmark must persist commit SHA, dirty state, host/JDK/storage identity,
database identity, all configuration, and sample/pair identity. The current
single-run artifact and printed reminder do not calculate or enforce the
ten-sample confidence gate.

`tools/tps-p4.sh` is not a normative runner until it enforces this complete
matrix. A ten-sample calculation for one River configuration is a useful point
calculator, but it must be labelled partial evidence and cannot emit a passing
Alpha3 result without the warmup, scaling, cross-engine, absolute, relative,
latency, failure, retry, invariant, and provenance checks above.

## No-go list

Until the corresponding evidence gate passes, do not:

- increase maximum retry attempts or backoff to improve apparent completion;
- interpret server-lifetime lock counters as measured-phase counters;
- use retained cycle exemplars as the denominator for victim or retry
  accounting;
- infer group eligibility, grouping success, cohort efficiency, or total force
  cost from the current `cohortCount`, `directFallbacks`, and group-only
  `forceCount` aggregates;
- merge a performance claim based on an unpaired smoke run;
- replace a scalability design with a benchmark-sized row, byte, pending-WAL,
  queue, cohort, or worker cap;
- treat one request per transaction as proof of lower service time;
- pass P0 with mixed JDBC/program isolation, change isolation merely to conceal
  the lock defect, or silently weaken program semantics;
- implement all remaining transaction programs before the Payment pilot;
- optimize sampled leaf methods without inclusive attribution;
- hard-code a buffer-pool percentage without admitted server/embedded memory
  budgets and measured cache behavior;
- add or remove TPC-C indexes without read cost, maintenance cost, touched-page,
  and split evidence;
- use table escalation, a lower worker limit, weaker isolation, or deferred
  durability to conceal a resource-order cycle;
- partition lock/page structures or add affinity/NUMA policy before a profile
  identifies that shared structure as a material limiter;
- call the current prototype a 50x performance milestone.

## Relevant source locations

- `tools/tps-test.sh`
- `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccTerminal.java`
- `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccRetry.java`
- `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccMetrics.java`
- `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccRiverNewOrder.java`
- `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccRiverNewOrderGraph.java`
- `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccRiverNewOrderStatements.java`
- `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccPayment.java`
- `river-bench/src/main/java/io/riverdb/bench/tpcc/TpccCustomerLookup.java`
- `river-engine/src/main/java/io/riverdb/engine/sql/SqlSessionExecutionCoordinator.java`
- `river-engine/src/main/java/io/riverdb/engine/table/IndexedGroupCommitCoordinator.java`
- `river-engine/src/main/java/io/riverdb/engine/table/IndexedGroupCommitBatch.java`
- `river-engine/src/main/java/io/riverdb/engine/table/IndexedHybridGroupPreflight.java`
- `river-engine/src/main/java/io/riverdb/engine/table/IndexedTransactionSession.java`
- `river-wal/src/main/java/io/riverdb/wal/local/LocalWal.java`
- `river-wal/src/main/java/io/riverdb/wal/local/LocalWalForceCoordinator.java`
- `river-jdbc/src/main/java/io/riverdb/jdbc/RiverJdbcConnection.java`
- `river-tx/src/main/java/io/riverdb/tx/LockExactDeadlockDetector.java`
- `river-tx/src/main/java/io/riverdb/tx/LockExactBlockerCursor.java`
- `river-tx/src/main/java/io/riverdb/tx/LockExactScheduler.java`
- `river-tx/src/test/java/io/riverdb/tx/LockExactDeadlockTest.java`
- `docs/plans/river-transaction-50x-optimization-route.md`
- `docs/plans/river-transaction-50x-optimization-route-review.md`
- `docs/plans/river-external-observability-tool-outline.md`
- `docs/plans/alpha3-tpcc-capacity.md`
- `tcpc-perf-notes.md`
