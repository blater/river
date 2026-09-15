# tic-gothmog: lock-order proof and measured CPU costs

This continues the [initial workload evidence](2026-09-15-tic-gothmog-transaction-costs.md)
after the user requested a mechanism-level conclusion. Baseline failures remain
primary evidence. Workload measurements still use the retained River ef935596
JVM distribution; the controlled regression exercises current checkout code.
No production optimization was implemented during this investigation.

## Lock-order mechanism

The common New-Order executor visits `input.Lines` in generated order, reads each
stock row with `FOR UPDATE`, updates it, and retains its write locks until the
transaction ends. Two orders in different districts can hold different district
locks while acquiring overlapping stock rows in opposite order:

- Transaction A holds stock X and requests Y.
- Transaction B holds stock Y and requests X.

The executor's per-transaction READ COMMITTED setting does not eliminate this
write-lock cycle. The workload retries the same logical input after rollback;
`--max-retries=3` permits four attempts with backoffs of 200, 400 and 800
microseconds. A retry does not change stock ordering. These facts explain a
credible mechanism for the stock-deadlock exemplars shared by both databases;
they do not reconstruct every individual benchmark cycle or prove a victim-policy
bug. Original workload ordering and retry defaults were preserved.

The focused `SqlReadCommittedLockOrderTest` uses the existing real-SQL concurrency
fixture and scheduler wait counts to control interleaving. Both transactions
verify effective READ COMMITTED. The opposite-order case checks the reciprocal
EXCLUSIVE/EXCLUSIVE edges on distinct row keys, one deadlock victim, rollback,
survivor completion, committed values, and immediate reuse of the victim session.
The matching-order case waits, completes both transactions, and selects no victim.
Fixture cleanup checks zero transactions, locks, waiters and retained snapshots.
Each case repeats twice: all four test invocations passed.

This is a current-code proof of the lock-order mechanism and its cleanup, not a
claim that every failed historical workload transaction followed these exact
resources. The stock workload's 135/126 River failures and 359 MariaDB failures
remain the independent workload observations.

## Matched process CPU controls

One worker, sample New-Order, one warehouse, seed 42, retries 3, READ COMMITTED,
20-second warmup and 30-second measurement. Both targets passed with zero retries,
terminal failures and unknown commits; expected rollbacks and deadline
cancellations remain included in phase CPU cost. Invariants and graceful cleanup
passed. Transport remains River TCP/TLS versus MariaDB Unix socket.

| Measurement | River | MariaDB |
| --- | ---: | ---: |
| Commits | 11,351 | 27,988 |
| Committed transactions/second | 378.45 | 932.93 |
| p50 / p95 / p99 latency, ms | 2.58 / 3.75 / 4.13 | 1.06 / 1.49 / 1.57 |
| Server process CPU delta, seconds | 26.98 | 19.16 |
| Client process CPU delta, seconds | 13.38 | 28.15 |
| Estimated server CPU, ms/commit | 2.38 | 0.68 |
| Estimated client CPU, ms/commit | 1.18 | 1.01 |

The temporary collector uses standard `ps` cumulative process CPU readings at
measured-phase start and post-workload phase transition. Read intervals are
retained in JSON. Start/end boundary offsets relative to the report window were
-7.06/+18.78 ms for River and -0.08/+12.65 ms for MariaDB. These are phase-bracket
estimates, not exact synchronized CPU counters. Process CPU includes background
threads, JIT/GC and transport work. The values show additional River server-process
computation in this pair, but do not isolate the engine from TLS or establish a
cross-system causal ratio. Longer warmup alone does not prove steady state.

Reports under `/Users/blater/src/ingres/river-harness/runs/`:

- River: `river_harness_20260915_170837_fb272217`.
- MariaDB: `river_harness_20260915_170954_fb49d322`.

Collector, commands, logs, CPU readings and timestamps are retained in
`/private/tmp/river-gothmog-deeper/`: `capture.py`, `river-cpu-control-capture.json`
and `mariadb-cpu-control-capture.json`. The collector forwards interruptions to
the harness so its owned lifecycle can clean up normally.

## River CPU profile

A separate identically configured River recording passed at 363.91 committed
transactions/second versus 378.45 in the unprofiled control. Estimated server CPU
was 2.70 ms/commit versus 2.38. This observed difference includes instrumentation
and run variation; it is not a measured profiler-overhead constant.

The recording contains 893 Java execution samples whose timestamps fall inside
the reported workload window. Three samples outside it were excluded. Sample
shares below are Java execution-sample shares, not fractions of total process CPU,
call counts or predicted TPS improvement.

| Leaf method | Samples |
| --- | ---: |
| `FormatBytes.checksum` | 64 (7.17%) |
| `TupleKeyCodec.compare` | 35 |
| `BTreePage.childForKey` | 33 |
| `IndexedPageFrameMap.find` | 30 |
| `BTreePage.lookupLeaf` | 18 |

Of the 64 checksum leaf samples, 60 have `TupleIndexRootRecordCodec.decode` as
caller. `IndexedTupleRootSnapshot` owns a reusable heap ByteBuffer holding a
216-byte record; the CRC covers 208 bytes. `FormatBytes.checksum` invokes
`CRC32C.update(int)` once per byte. Both that helper and the root snapshot code
are unchanged between measured ef935596 and current HEAD.

The first narrowly evidenced CPU candidate is bulk CRC32C over an accessible
heap backing array, preserving the existing checksum, validation and ByteBuffer
position/limit/mark. Removing corruption validation or sharing mutable buffer
state is not justified. Other sampled lookup/compare costs require their own
analysis before selecting a change; no claim is made that CRC explains the
whole River/MariaDB gap.

Artifacts under `/private/tmp/river-gothmog-deeper/`:

- `river-cpu-profile.jfr`, `profile.jfc`, profile command/log/capture JSON.
- `cpu-samples.json` and `cpu-sample-summary.json`, with measured-window filtering.
- `hot-methods.txt`, an unfiltered standard JFR view retained for inspection.

Profile report: `river_harness_20260915_171107_b326d998` under the harness runs
root. No JFR allocation, native wait, socket, or FileForce totals are treated as
complete process CPU or total mapped-WAL barrier costs.

## Isolated checksum probe and decision

A temporary Java probe called the retained distribution's actual
`FormatBytes.checksum` and compared it with `CRC32C.update(byte[], offset, length)`
over a heap slice, including its nonzero array offset. All 6,897 combinations
of offsets 0–32 and lengths 0–208 matched and preserved position, limit and mark.
After alternating warmup, four one-million-iteration samples used current / bulk /
bulk / current order and mutated one input byte each iteration:

| Method | First sample, ns/208-byte record | Second sample, ns/208-byte record |
| --- | ---: | ---: |
| Current bytewise | 389.86 | 388.16 |
| Bulk array | 11.32 | 11.37 |

This is an isolated diagnostic probe, not JMH or a transaction benchmark. It
supports the direction and mechanism of the candidate, not a predicted workload
speedup. It covers the observed accessible heap-buffer path; production delivery
must also preserve direct/read-only buffer support, byte order, range semantics,
and corruption rejection. Source/output: `ChecksumProbe.java` and
`checksum-probe.txt` in the temporary artifact directory above.

Decision: recommend bulk heap-array CRC32C as the first bounded implementation
candidate, owned by river-format. Preserve the on-disk checksum and all admission
checks. A later feature must pass format corruption/buffer tests and matched
River before/after workload samples before acceptance. No production optimization
was made here. The whole cross-database gap remains unattributed.

Independent reviewer `strategy_adversary` accepted this candidate with the sample
share and observer-effect qualifications above. Its concurrency review requested
an assertion of the victim's rolled-back value before the survivor overwrites it;
that assertion was added and all four focused test invocations passed again.
The full `./gradlew --no-daemon :river-engine:test` suite passed before that added
assertion. Builds, workloads and the checksum probe ran sequentially.

## Why root decoding checksums, and potential repeated admission

The durable record names a B-tree root page, generation, owner and key descriptors.
Page admission validates page identity and CRC; it does not perform the record's
magic/version/checksum and semantic admission. The current root decoder supplies
that typed trust boundary. Removing its CRC without replacing the boundary is
not justified by this investigation.

However, every prefix probe and scan open resolves the visible registry row,
copies it and performs that full decode again. There is no retained validated
record identity check. Revalidation of an unchanged admitted version is therefore
a potential separate optimization, not an intrinsic requirement of each lookup.
The independent reviewer traced a specific hazard: vacuum remaps physical version
row IDs, so row ID alone cannot identify a reusable validated version. Reuse would
need table/kernel lifetime, key identity and exact resolved version/commit, with
invalidation across relevant vacuum, recovery, reset and publication epochs.
It must own decoded values, propagate the current observed commit sequence and
retain caller ownership/schema/shape/lifecycle checks. This requires a concurrency
and lifetime design; the bulk checksum candidate needs no such new cache contract.

## Subsequent user-directed implementation

After the investigation, the user explicitly requested removal of whole-page
read CRC and repeated root CRC work, with no further measurements. The resulting
implementation supersedes the earlier bulk-root-CRC recommendation:

- Page v4 CRC32C covers only the 120-byte header prefix on encode and decode.
  The whole 16 KiB checksum is removed on reads and writes.
- Tuple-root v4 records are 208 bytes; CRC fields, arguments, computations and
  all seven callers' dedicated CRC objects are deleted. Semantic validation
  remains. No validated-root cache or cache invalidation contract was added.
- Remaining accessible heap-buffer CRCs use bulk array updates, respecting
  array offsets and buffer limits. Direct/read-only buffers retain the
  allocation-free absolute-read path. The CRC32C polynomial is unchanged.
- Old page/root versions are rejected. ADR 0004/0005 describe the intentionally
  reduced payload-corruption detection; WAL checksums remain intact.

Shrinking root records exposed a real heap-allocation boundary during DDL
rollback. Registry growth after tuple-page reclamation tried to allocate a
newly staged free page through the committed-page reader. Registry growth now
precedes reclamation in both live compilation and WAL replay. The existing
foreign-key rollback regression fails with the old ordering and passes with
the fix. A temporary 216-byte control isolated record packing as the trigger;
that control and all temporary tracing were removed. No padding is retained.

Focused rollback/restart, checkpoint/header repair, and relational WAL recovery
tests passed. The WAL-only mid-DROP fixture covers replay/free-chain recovery;
it does not explicitly force heap growth at that replay boundary. Independent
reviewer `strategy_adversary` approved the format changes and matching live/replay
ordering. Correctness tests replaced measurement runs at the user's direction.

The replay ordering was simplified to one registry stage followed by cleanup,
independently reviewed for both reclaim and finish transitions. Slopmark flagged
the initial duplicate guarded cleanup calls (5 → 90.60); the simplified version
returns to 5 without a second policy path. No benchmark was run.

Validation completed: full affected suites passed (engine 1,042; format 83;
storage 47; WAL 49; inspect 4; server-app 75 — 1,300 tests, zero failures).
After the final replay simplification, the five focused engine classes for
foreign-key rollback, embedded recovery, single-page repair, relational WAL
recovery and checkpoint lifecycle passed again. `git diff --check` passed.
Subsequent commit/merge delivery is recorded in
[tic-fine-barad-dur](../../tickets/tic-fine-barad-dur.md); no installed executable
was replaced.
