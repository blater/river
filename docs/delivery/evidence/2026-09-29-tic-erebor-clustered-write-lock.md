# Erebor clustered write-lock boundary

Date: 2026-09-29 UTC. Branch: `feature/tic-erebor-clustered-row-store`.

The first clean full `check` found that New Order could finish while Payment
held the warehouse row's exclusive logical lock. A focused rerun reproduced
the failure: the New Order worker returned `true` before any expected lock
waiter appeared. Non-key updates kept the logical-row lock but skipped the
unchanged clustered tuple key, while a serializable point read protected that
tuple key's range. The two locks therefore did not conflict.

`RelationalDescriptorTupleDeltaProtection` now takes the clustered tuple-key
exclusive lock for non-key replacements. It remains in the existing tuple
protection owner and does not consult the identity mapping. It adds one tuple
lock acquisition per such update and can add one lock wait in the contended
New Order/Payment schedule. The fix changes no tuple page, identity-map, WAL
or history mutation count for a non-key update. The cost of the additional
lock acquisition has not been isolated by a throughput comparison.

Focused results after the fix:

- `TpccConcurrencyReproducerTest` passed both repetitions. New Order waits on
  Payment's exclusive clustered key; the three-client schedule can also make
  the later Payment wait behind New Order's serializable read.
- `SqlGeneralConcurrencyTest` passed all four methods. Its point-cycle
  diagnostics accept either the logical-row or clustered-key resource as the
  first exclusive conflict, while checking the owner pair, modes, queue,
  resource identity and deadlock cleanup.
- The serial clean `check` passed the bench module. It reached 609 engine
  tests with one point-cycle diagnostic expectation failure, then stopped.
  The affected SQL concurrency class passed after that expectation was
  corrected. A second serial clean full `check` passed in 3m 58s:
  156 actionable tasks, 99 executed, 55 from cache and 2 up to date.

One River-specific local write-path diagnostic ran at 2026-09-29 17:51:43 UTC
from source `64c299ac`, GraalVM 25.0.4 JVM on macOS/arm64. The exact command and
published artifact are retained in
`/private/tmp/river-erebor-write-lock-tps/run-metadata.properties` and
`/private/tmp/river-erebor-write-lock-tps/tpcc-acceptance.properties`.
The command selected `tiny`, New Order/Payment 50/50, serializable isolation,
no-wait stress scheduling, four terminals, one warehouse, seed 42, two seconds
warmup and ten seconds measured. It reported `status=OK`, 5,332 committed
transactions (2,739 New Order and 2,593 Payment), 533.200 commits/s, zero
whole-transaction retries, zero failed outcomes, passed pre/post invariants,
`deadlock_reconciliation=OK`, `performance_capture=OK` and zero active
transactions/locks after owned cleanup. The preflight deadlock probe recorded
its one expected victim; the measured capture recorded zero deadlocks.

The host was in low power mode during this work. These are correctness and
lock-path results. This one short TPS sample is not compared with older runs,
is not a new baseline and does not isolate the extra lock's throughput cost.
