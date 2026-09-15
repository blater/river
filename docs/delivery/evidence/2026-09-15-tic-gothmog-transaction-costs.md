# tic-gothmog transaction-cost investigation

## Historical execution status

This file retains the initial historical-build runs. The subsequent current-build
comparison and final recommendations are recorded in
[the final comparison](2026-09-15-tic-gothmog-current-comparison.md).

The initial bounded investigation ran on one macOS arm64 host. Runs were serialized
through the installed `river-harness` command, with fresh harness-owned target
instances and normal validation, drain, and cleanup.

Selected River artifact (historical stable build):

- executable: `/Users/blater/src/river-performance-evidence/20260913-da4e/river`
- executable SHA-256: `38b637e2d53ea55e743e4fce71179a56dee9e77765fd74795029dc24368e00c5`
- source label: `master-ef935596`; commit:
  `ef935596225eb78cf400379bfbb5d0d78de4a530`
- reported distribution version: `0.1.0-alpha.2`, River protocol v5
- the source commit exists locally and is an ancestor of current `HEAD`
  `eede02521803a148abf09a8df8155c33e8d9affe`; this investigation does not
  claim the result represents later current-master fixes.

The checkout contains unrelated uncommitted documentation, descriptor, and
production edits. No build was run and none of those edits entered the selected
artifact. The harness build identity in each report is
`aba7c43f459afd7452bd47e345d6e88d76788fa8+dirty`; this describes the harness
checkout and is unchanged across runs. The harness is report schema v2. Its measured report has committed,
expected-rollback, failed, unknown-commit, retry, and p50/p95/p99 counters, but
no process CPU or CPU-millisecond counters. CPU collection is therefore
recorded as unavailable rather than supplemented with new instrumentation.

The workload latency timer starts before the logical transaction retry loop and
is recorded once after the final outcome. It includes retry/backoff time and
covers committed, expected-rollback, failed, unknown, and cancelled outcomes;
it excludes input generation and executor preparation. Latency is therefore
not a successful-commit-only population.

Effective matched workload settings: sample profile, new-order only, one
warehouse, seed 42, 1% deliberate rollback, 5s warmup, 30s measured, and
`--max-retries=3`. Both bindings admit READ COMMITTED with explicit `FOR
UPDATE` locks and require flushed commit durability (`flush_at_commit=1` for
MariaDB; River reports local durable WAL). Transport differs: MariaDB uses a
private Unix socket; River uses authenticated loopback TCP/TLS 1.3. MariaDB's
environment file also records its global/session default `REPEATABLE-READ`, but
the admission probe and effective run contract are READ COMMITTED.

## Baseline observations

| sequence | target | workers | report | status | committed TPS | p50 | p95 | p99 | attempts | commits | expected rollbacks | retries | cancelled | failures | unknown | comparison key |
| --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | River | 1 | `river_harness_20260915_164755_ef600941` | passed, cleanup verified | 376.24 | 2.605055 ms | 3.780607 ms | 4.259839 ms | 11,387 | 11,285 | 101 | 0 | 1 | 0 | 0 | `05f20bc0068d89fee38a6b0139663924924f4f8a3d27b66756242eecbe6ccea6` |
| 2 | MariaDB | 1 | `river_harness_20260915_164840_6d0e0bd5` | passed, validation and cleanup verified | 912.47 | 1.079295 ms | 1.538047 ms | 1.674239 ms | 27,619 | 27,374 | 244 | 0 | 1 | 0 | 0 | `05f20bc0068d89fee38a6b0139663924924f4f8a3d27b66756242eecbe6ccea6` |
| 3 | MariaDB | 1 | `river_harness_20260915_165140_6f000698` | passed, validation and cleanup verified | 943.96 | 1.044479 ms | 1.473535 ms | 1.544191 ms | 28,575 | 28,319 | 255 | 0 | 1 | 0 | 0 | `05f20bc0068d89fee38a6b0139663924924f4f8a3d27b66756242eecbe6ccea6` |
| 4 | River | 1 | `river_harness_20260915_165232_e86a842d` | passed, cleanup verified | 354.20 | 2.676735 ms | 4.052991 ms | 4.882431 ms | 10,722 | 10,623 | 98 | 0 | 1 | 0 | 0 | `05f20bc0068d89fee38a6b0139663924924f4f8a3d27b66756242eecbe6ccea6` |
| 5 | River | 4 | `river_harness_20260915_165340_d185ce3d` | failed; cleanup/validation verified; retry exhaustion | not rankable | 7.987199 ms | 18.399231 ms | 24.117247 ms | 19,027 | 13,027 | 120 | 5,742 | 3 | 135 | 0 | not comparable |
| 6 | MariaDB | 4 | `river_harness_20260915_165539_4d2220ee` | failed; cleanup/validation verified; retry exhaustion | not rankable | 2.480127 ms | 6.836223 ms | 9.469951 ms | 55,899 | 39,521 | 390 | 15,626 | 3 | 359 | 0 | not comparable |
| 7 | River | 4 | `river_harness_20260915_165725_a4d8d740` | failed; cleanup/validation verified; retry exhaustion repeat | not rankable | 7.725055 ms | 17.448959 ms | 23.101439 ms | 19,888 | 13,638 | 127 | 5,993 | 4 | 126 | 0 | not comparable |

The four one-worker reports are passed, validated, cleaned up, and have the
same eligible comparison key. No ratio is reported for an ineligible or failed
run. Each listed report ID is under
`/Users/blater/src/ingres/river-harness/runs/<run_id>`.

## Four-worker bounded control

The first four-worker River run exhausted its retry budget: 135 measured
terminal failures (all retained errors are River `DEADLOCK (4002)` on stock),
with 5,742 retries and 13,027 commits. Validation and graceful cleanup still
passed, but the run is not comparable and its TPS/latency is not rankable.

The one permitted matched follow-up, MariaDB/4 workers, also exhausted the
same retry budget. It recorded 359 measured terminal failures (retained errors
are MariaDB `Error 1213 (40001)` deadlocks on stock), 15,626 retries, and
39,521 commits. Validation and graceful cleanup passed. This shared failure at
the four-worker sample hot set supports a contention/retry boundary shared by
the two target paths; it does not establish a throughput comparison.

The exact River/4 repeat then reproduced the same failure mode: 126 measured
terminal failures, 5,993 retries, and 13,638 commits, with repeated River
`DEADLOCK (4002)` on stock. Warmup failures remain separate: River first 9
failures/571 retries (1,818 attempts), MariaDB 67/2,698 (9,692 attempts), and
River repeat 13/577 (1,836 attempts). All three four-worker runs passed
post-run validation and graceful cleanup, but all are excluded from successful
throughput ranking. Execution ended after the user-directed control and repeat.

| four-worker measured run | terminal logical outcomes (attempts - retries, including cancellations) | failures / terminal outcomes | retries / commit |
| --- | ---: | ---: | ---: |
| River first | 13,285 | 135 / 13,285 = 1.0162% | 5,742 / 13,027 = 0.4408 |
| MariaDB control | 40,273 | 359 / 40,273 = 0.8914% | 15,626 / 39,521 = 0.3954 |
| River exact repeat | 13,895 | 126 / 13,895 = 0.9068% | 5,993 / 13,638 = 0.4394 |

The normalized figures use logical terminal outcomes, so raw failure counts are
not ranked across targets with different completed-work volumes.

## Supplemental process-cost controls

To separate process CPU from end-to-end wait cost, three additional runs used
the same harness settings; River runs used the historical executable selected
above. Each used a 20s warmup, 30s measured window, one worker, one warehouse,
seed 42, READ COMMITTED, explicit `FOR UPDATE`, and three retries. Each was
serialized and
passed validation and graceful cleanup. The harness recorded `ps` CPU time at
the measured-window boundaries; boundary offsets were under 19ms, so the
following are phase-bracket estimates rather than exact transaction counters:

| target | report | measured commits | committed TPS | p99 | client CPU delta / commit | server CPU delta / commit |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| River | `river_harness_20260915_170837_fb272217` | 11,351 | 378.45 | 4.126719 ms | ~1.18 ms | ~2.38 ms |
| MariaDB | `river_harness_20260915_170954_fb49d322` | 27,988 | 932.93 | 1.570815 ms | ~1.01 ms | ~0.68 ms |

The corresponding process CPU deltas were River client 13.38s/server 26.98s
and MariaDB client 28.15s/server 19.16s over their measured brackets. The
River server process therefore consumed more bracket CPU per commit in this
transport pair, while the MariaDB client process handled more commits and
consumed more total client CPU. These values include protocol, runtime, GC,
and other process work; they do not isolate engine CPU or establish causality.
The River controls had no retries or failures; MariaDB's environment default
field remains `REPEATABLE-READ`, while its admission probe was effective READ
COMMITTED. Raw capture records are outside Git at
`/private/tmp/river-gothmog-deeper/{river-cpu-control,mariadb-cpu-control}-capture.json`.

The final River control also recorded a JFR profile during its measured window:
`/private/tmp/river-gothmog-deeper/river-cpu-profile.jfr`, paired with report
`river_harness_20260915_171107_b326d998` and capture metadata
`/private/tmp/river-gothmog-deeper/river-cpu-profile-capture.json`. The
recording contains execution/native samples, thread parks, socket writes,
allocation statistics, and nine `jdk.FileForce` events. `FileForce` is not a
total WAL-force counter because mapped barriers can be omitted, so the profile
is retained for qualitative follow-up only.

## Mechanism trace and smallest controlled probe

The sample run uses `FullInputGenerator.newOrder` with 5–15 lines. Each worker
has an independent SplitMix64 stream (`full_plan.go:35-42`), and
`full_inputs.go:67-96` appends item IDs in generated order while a `seen` map
deduplicates only within that one input. There is no global item ordering; at
one warehouse all supply rows are local. The scheduler calls `Prepare` once per
logical operation (`full_plan.go:56-61`), then retries `Execute` on that same
prepared input (`mixed_worker.go:66-93`).

The common SQL New-Order path first locks and advances the district row, then
walks `input.Lines` in order (`sqlfull/transaction_new_order.go:12-27`). For
each line it reads the item, locks `stock(s_w_id,s_i_id)` with `FOR UPDATE`,
updates that row, and inserts the order line (`:65-122`; statement catalogue
`sqlfull/transaction_catalogue.go:45-51`). With overlapping items, a minimal
cycle is transaction A locking stock `[A,B]` while transaction B locks `[B,A]`:
each waits on the other's second row while retaining its first. Per-input
deduplication does not prevent this cross-transaction order inversion.

Both adapters use the same common SQL binding and rollback lifecycle. A
retryable execution error rolls back before the scheduler waits 200µs, 400µs,
800µs, then up to 10ms and retries the unchanged input, up to three retries
(`mixed_worker.go:74-101`; `sqlfull/transactions.go:74-102`). River maps status
4002 `DEADLOCK` to retryable (`dbms/river/protocol_status.go:61` and
`dbms/river/database.go`); MariaDB maps native 1020, 1205, and 1213 to
retryable (`dbms/mariadb/database.go:216-223`). Focused tests cover generator
determinism/deduplication (`suite/tpcc/new_order_test.go`) and adapter outcome
classification (`binding/tpcc/mariadb/new_order_test.go`), but no existing
reverse-order two-transaction fixture was found.

The minimum causal probe is a diagnostic fixture against each target at
READ COMMITTED using two dedicated transactions and two stock rows A and B:

1. In the same-order control, both transactions acquire `[A,B]`, with a barrier
   after A; one waits for A, then completes after the first commits, and neither
   reports a deadlock.
2. In the reverse-order case, transaction A acquires `[A,B]` and transaction B
   acquires `[B,A]`, again synchronizing after the first lock. Record the
   server error, rollback result, wait/deadlock timing, and final row values.

The fixture must use the existing River and MariaDB adapters, explicit
`sql.LevelReadCommitted`, and the same `FOR UPDATE` stock statements. It should
assert one retryable deadlock in the reverse case and no deadlock in the
same-order control, while checking both transactions leave no partial update.
This isolates lock acquisition order without sorting real TPC-C inputs or
changing workload defaults. A fixture needs separate approval/coordination; it
was not run for this evidence.

A separate current-checkout focused test,
`io.riverdb.engine.sql.SqlReadCommittedLockOrderTest`, drove the same reverse
row-order cycle and matching-order control through `SqlConcurrencyFixture`
under READ COMMITTED. The matching-order control passed. The reverse test
reached a valid two-edge reciprocal cycle and released the victim, with
diagnostics enabled/valid, one victim selection/outcome, one queued-request
cancellation, two holdings released, and zero self-validation or overflow
failures. The initial run exposed a stale expected edge mode (`UPDATE` versus
the current exact `EXCLUSIVE` requested/held mode); after that test-only
expectation was corrected, both repetitions passed. The focused command and
the full `./gradlew --no-daemon :river-engine:test` suite passed. This is
current-code mechanism evidence, separate from the historical timing samples;
no production code was changed.

## Accounting and interpretation

For every measured report, attempts reconcile to terminal accounting. The
passed one-worker rows satisfy `attempts = committed + expected_rollbacks +
cancelled + retries`: 11,387 = 11,285 + 101 + 1 + 0; 27,619 = 27,374 + 244 +
1 + 0; 28,575 = 28,319 + 255 + 1 + 0; and 10,722 = 10,623 + 98 + 1 + 0.
The failed four-worker rows also reconcile when terminal failures are included:
19,027 = 13,027 + 120 + 5,742 + 3 + 135; 55,899 = 39,521 + 390 + 15,626
+ 3 + 359; and 19,888 = 13,638 + 127 + 5,993 + 4 + 126. Unknown commits
were zero in every run. Cancellations are deadline accounting, not commits or
retries: one per passed one-worker measured window, three for the first
four-worker River and MariaDB windows, and four for the River repeat.

The command template for every run was:

```text
/Users/blater/src/ingres/river-harness/benchmark run {river|mariadb} tpcc sample new-order \
  [--river-executable=/Users/blater/src/river-performance-evidence/20260913-da4e/river \
   --river-version=master-ef935596-tic-gothmog] \
  --warmup=5s --duration=30s --workers={1|4} --warehouses=1 --seed=42 --max-retries=3
```

River used JVM mode through the selected wrapper's fixed GraalVM 25.0.4 runtime
and `-Xmx1g`; MariaDB used Homebrew MariaDB 12.3.3 with `innodb_flush_log_at_trx_commit=1`.
The River runtime reports local durable WAL and authenticated loopback TLS 1.3;
MariaDB reports flushed commit log durability and a private Unix socket. Both
effective workload contracts are READ COMMITTED with explicit `FOR UPDATE`
locks. The MariaDB global/session default `REPEATABLE-READ` is retained as an
environment setting; the admission probe and transaction binding set and
verify READ COMMITTED. The common SQL transaction starts at
`internal/binding/tpcc/sqlfull/transactions.go:54` with
`sql.LevelReadCommitted`; the River driver path is
`internal/dbms/river/driver.go:113` and sends `BEGIN READ COMMITTED`; the
MariaDB binding uses `sql.LevelReadCommitted` at
`internal/binding/tpcc/mariadb/binding.go:110`, and the MariaDB admission probe
records the effective level. These source links explain why the default setting
is not the effective comparison isolation.

The report's v2 latency histogram is logical transaction execution from before
the retry loop through its final outcome, including retry/backoff and all
terminal populations. It excludes input generation and executor preparation.
Warmup versus measured committed TPS was River 267.60 versus 376.24 and
243.39 versus 354.20 at one worker; at four workers it was 244.71 versus 434.40
on the first River run and 246.25 versus 454.89 on the repeat. These short
windows show a repeated warmup/measured difference, but do not prove measured
window trend or steady-state JIT/cache settling after a 5s warmup. The
single-worker result is therefore a repeatable short-window end-to-end
observation, not a steady-state engine CPU-cost claim.
Because the failed four-worker runs are ineligible for successful-throughput ranking, their latency values
are descriptive only. The report has no server/client CPU-millisecond,
allocation, WAL-force, statement-count, or page-access counters. CPU and those
mechanism counters are unavailable in the initial seven reports. Subsequent process CPU collection and JFR evidence are documented above and in the mechanism follow-up.

## Initial seven-run decision (superseded by mechanism follow-up)

| rank | observation or hypothesis | evidence and confidence | decision/use |
| ---: | --- | --- | --- |
| 1 | Four-worker lock contention and retry exhaustion is repeatable in River and shared by MariaDB at the sample hot set. | River exact repeat: 135 then 126 measured failures, 5,742 then 5,993 retries, repeated DEADLOCK 4002 on stock; MariaDB: 359 failures, 15,626 retries, repeated SQLSTATE 40001 deadlock on stock. Normalized failure rates using terminal logical outcomes (attempts minus retries, including deadline cancellations) are 1.0162%, 0.9068%, and 0.8914%; retries/commit are 0.4408, 0.4394, and 0.3954. High confidence for this bounded configuration. | Defer optimization attribution; the next correctness probe belongs to the shared harness stock lock acquisition/order and transaction retry owner. Inspect deadlock victim/order and retry accounting. |
| 2 | River has substantially higher single-worker transaction latency and lower committed throughput under the selected transport pair. | Two River runs: 354.20/376.24 TPS, p99 4.882/4.260ms. Two MariaDB runs: 943.96/912.47 TPS, p99 1.544/1.674ms. The direction repeats, but Unix socket versus TLS TCP and missing CPU counters limit causal confidence. | Defer implementation; do not assign the gap to engine, protocol, or durability without matched transport and CPU/statement evidence. |

Decision: defer a production optimization. The bounded result supports
repeatable River four-worker retry exhaustion and shared four-worker contention,
plus a repeatable single-worker cost gap. Transport and missing mechanism
counters prevent selecting one addressable subsystem with the confidence
required for implementation. The smallest next correctness probe is shared
harness stock lock acquisition ordering plus deadlock-victim and
retry/terminal-outcome accounting; it must not infer a River deadlock detector
defect when both targets fail. CPU, transport, or WAL instrumentation is a
separate follow-up. Conclusions apply only to sample New-Order at one and four
workers on this historical `master-ef935596` River build, not to TPC-C overall
or current master.

## Independent evidence review

The lead independently checked effective isolation, comparison keys, all seven
outcome equations, invariants and graceful cleanup. Reviewer `strategy_adversary`
accepted the revised evidence and bounded defer decision on 2026-09-15. Review
corrections retained failed runs as primary evidence, normalized failure counts,
removed unsupported WAL attribution, and disclosed warmup uncertainty. No further
runs or new instrumentation were required to support this bounded conclusion.

## Completed mechanism follow-up

The user requested continued investigation after the initial defer decision.
The [mechanism evidence](2026-09-15-tic-gothmog-mechanisms.md) supersedes that
initial next-step recommendation with controlled lock-order tests, matched
process CPU controls and a measured River checksum candidate. The original
failed runs and initial review remain preserved above.
