# Erebor final write-cost evidence

Branch: `feature/tic-erebor-clustered-row-store`. Final candidate source:
`46e5ab39c35004759f617d29961d777c7791636e`; stable control: `2ada6350`.
The host is in low power mode. The owner permits one passing sample per build
and workload for this acceptance decision. Stock Level's earlier accepted
decision remains in [its original evidence](2026-09-29-tic-erebor-candidate-stock-level.md).

## Physical identity-map cost

A temporary instrumented build at `0b2e7bbb` ran actual descriptor DML,
commit, immutable-page publication and flush. The fixture has two BIGINT
columns, 128 initial rows, no secondary indexes and a declared BIGINT primary
key. Each measured transaction changes eight rows. An admitted older reader
first resolves a stable identity and releases its row pin; its snapshot
continues to require the older generation. These transactions do not split
the map leaf. The hidden-primary control uses the same columns and initial
cardinality, with its identity tree serving as the canonical clustered store.
The later catalogue-scan correction changes none of these map or lock operations.

| Eight-row transaction | Dirty map pages | Map page copies, bytes | Retained map frames | Map flush bytes | Map logical WAL bytes | Map payload bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Declared primary: insert | 1 | 32,768 | 1 | 16,384 | 1,028 | 336 |
| Declared primary: non-key replacement | 0 | 0 | 0 new | 0 | 0 | 0 |
| Declared primary: primary-key move | 1 | 32,768 | 1 | 16,384 | 1,028 | 336 |
| Declared primary: delete | 1 | 32,768 | 1 | 16,384 | 860 | 168 |
| Hidden primary: insert, canonical store | 1 canonical | 32,768 canonical | 1 canonical | 16,384 canonical | 996 canonical | 304 canonical |

The hidden-primary row has **zero additional identity-map trees or costs**.
Its last row describes its canonical row tree, for comparison with the
additional locator tree in the declared-primary case. A retained frame owns
16,384 bytes. No page is added in these warmed, unsplit transactions.

The copy probe counts full page copies in staging and publication by the
page's owner key ID. The old current frame supplies a stable snapshot; the
staged frame is writable only within the commit operation, and the published
frame becomes immutable. The flush probe counts successful map-owned page
writes through the actual frame I/O boundary. WAL attribution includes the
map descriptor, its suboperation and each map mutation's header/key/locator;
it excludes shared WAL framing and shared scalar registry/allocation metadata.
Those shared costs are not claimed to be zero or assigned exclusively to the
map. Splits and composite locators change these figures.

The measured payload count is copied into the compilation arena and again
into reserved WAL payload storage. The compiler also loads keys/values into
its reusable scratch buffers for physical application and WAL compilation.
These bounded copies preserve the journal, operation and durable-buffer
lifetimes; they are distinct from the 32 KiB page copies. This is a named
boundary count, not an exhaustive transaction-wide copy total.

The exact fixture, instrumentation scripts/diff, source/JAR identities and
JUnit result are retained in `/private/tmp/erebor-write-cost/`, including
`physical-cost-results.xml`, `metadata.json` and `instrumentation.diff`.
The passing capture followed corrections to temporary fixture setup and the
hidden-primary API usage. All temporary production instrumentation was removed
before the clean integration check and ordinary workload measurements.

## Additional replacement lock

The declared-primary fixture warmed 100 replacement transactions, then
measured 100 eight-row transactions. It observed exactly 800 additional
clustered tuple-key protection calls and 4,443,000 ns of caller-thread CPU,
about 5.55 microseconds per call. Each row is updated once per transaction,
so these include fresh protection acquisition. The calls remain necessary
to conflict with serializable readers; they make no identity-map mutation.
This single platform-thread fixture is an uncontended mechanism diagnostic,
not server CPU or a contended-lock latency claim.

The compiler runs on a virtual commit thread. Its temporary ThreadMXBean
field produced zero and is **discarded as unavailable**, not reported as
zero CPU cost. Server/virtual-thread attribution uses the separate JFR passes
below. The production candidate contains neither these timers nor JFR scope
events.

## Named workload controls and server CPU

All runs used harness `5082670`, GraalVM 25.0.4, `-Xmx1g`, macOS/arm64,
READ COMMITTED with explicit FOR UPDATE, local durable WAL, loopback TLS,
one warehouse and seed 42. Runs, builds and profiling passes were serialized.
The ordinary control executable is `/private/tmp/river-ent-candidate/river`;
the final candidate is `/private/tmp/river-erebor-promotion-final-dist/river`.
`final-distribution.json` retains the source and all distributed River JAR
SHA-256 values. The production engine JAR contains no temporary cost probe.

The command is `benchmark run river tpcc sample CATEGORY` with the executable
above, the version label below, `--warehouses=1 --seed=42`, and the window,
workers and retry limit in the table. Exact command arrays, labels, timestamps
and artifact paths are retained in `*-summaries.json` below
`/private/tmp/erebor-write-cost/`. Artifact IDs below are immutable directories
under `/Users/blater/src/ingres/river-harness/runs/`.

Individual short runs use five seconds warmup, 20 seconds measured, one
worker and three retries. Controls preceded the catalogue correction; their
unchanged build and manifest match the final candidates. Short runs happened
at 19:37–19:58 UTC (20:37–20:58 BST). Variation is substantial; these are
engineering diagnostics, not a new TPS baseline or a general speedup claim.

| Category | Build/source | Commits/s | p99 ms | Artifact ID |
| --- | --- | ---: | ---: | --- |
| New Order | control `2ada6350` | 284.046 | 7.610 | `river_harness_20260929_193659_7d6e4184` |
| New Order | final `46e5ab39` | 208.347 | 10.371 | `river_harness_20260929_195644_09e2dc37` |
| Payment | control `2ada6350` | 874.877 | 2.193 | `river_harness_20260929_193828_3d773d36` |
| Payment | final `46e5ab39` | 837.335 | 2.204 | `river_harness_20260929_195714_2a52da4e` |
| Order Status | control `2ada6350` | 8,231.325 | 0.216 | `river_harness_20260929_193858_d80544ca` |
| Order Status | final `46e5ab39` | 7,465.065 | 0.244 | `river_harness_20260929_195745_902c7294` |

Control labels are `erebor-promotion-ordinary-2ada6350-CATEGORY-control`;
final labels are `erebor-promotion-final-46e5ab39-CATEGORY-candidate`, using
`new-order`, `payment` or `order-status` for CATEGORY. Each passed invariants,
reported zero failed/unknown outcomes and zero retries, shut down gracefully
and removed its owned data directory. Each final run accounted for one
measured-window cancellation. Comparison eligibility is `eligible` and the
keys match within each family: New Order `fd1585b9…`, Payment `13102cbe…`,
Order Status `7ac8391e…`; the full keys remain in the reports.

### Targeted New Order follow-up

The continuing short New Order decline triggered one longer, reversed-order
pair: ten seconds warmup, 60 seconds measured, otherwise the same one-worker
manifest and retry limit three. It ran at 20:05–20:08 UTC (21:05–21:08 BST).

| Build | Exact version | Commits/s | p99 ms | Artifact ID |
| --- | --- | ---: | ---: | --- |
| Final candidate | `erebor-promotion-final-long-new-order-46e5ab39-new-order-candidate` | 373.911 | 4.289 | `river_harness_20260929_200534_fd9e2ddf` |
| Stable control | `erebor-promotion-final-long-new-order-2ada6350-new-order-control` | 278.695 | 5.870 | `river_harness_20260929_200649_684a1805` |

Both reports passed, were eligible with identical keys, had zero retries and
failed/unknown outcomes, passed invariants and completed owned cleanup.
The short candidate-specific decline did not repeat in this longer pair.
The individual results are retained; the longer pair does not establish an
isolated identity-map CPU improvement or a general throughput ratio.

### Four-worker mixed retry boundary

The required five/20-second `sample all`, four-worker, retry-limit-three
candidate stopped during warmup after exhausting New Order deadlock retries.
The stable control did so twice, and the candidate did so before and after
the catalogue correction. Retained failed artifact IDs are
`river_harness_20260929_193958_da4ef5f5` (initial candidate),
`river_harness_20260929_194128_91491366` and
`river_harness_20260929_194233_30e45aa5` (controls),
`river_harness_20260929_194241_8180e29e` (candidate repeat), and
`river_harness_20260929_195815_73bf813d` (final candidate). All unknown counts
were zero and owned shutdown/cleanup completed. These failed runs are not
eligible comparisons and do not satisfy the strict acceptance manifest.

One targeted matched diagnostic changed only the retry limit to ten. All five
families ran with four workers, the same sample data and seed, five seconds
warmup and 20 seconds measured, at 20:02–20:03 UTC (21:02–21:03 BST).

| Build | Exact version | Commits/s | p99 ms | Retries | Artifact ID |
| --- | --- | ---: | ---: | ---: | --- |
| Control | `erebor-promotion-final-mixed-2ada6350-all-control` | 348.186 | 53.641 | 1,165 | `river_harness_20260929_200231_1557af97` |
| Final candidate | `erebor-promotion-final-mixed-46e5ab39-all-candidate` | 353.348 | 52.068 | 1,111 | `river_harness_20260929_200301_37c90d04` |

Both passed all invariants, reported zero failed/unknown outcomes, stopped
gracefully and removed their owned directories. Four control and three
candidate attempts were cancelled at the measurement boundary. Both are
eligible with key `533b0546…`. The retry count is reported explicitly, not
discarded. These results show successful mixed recovery within ten retries;
they do not waive the ticket's three-retry acceptance condition. That
condition needs owner/review resolution before promotion.

### Measured catalogue scan correction

The first candidate (`0b2e7bbb`) passed the individual workload invariants but
was slower than the stable control: New Order 203.971 versus 284.046 commits/s,
Payment 675.326 versus 874.877, and Order Status 4,671.580 versus 8,231.325.
These measurements and all reports remain retained in
`/private/tmp/erebor-write-cost/ordinary-summaries.json`.

Separate matched JFR diagnostics captured virtual connection and commit
threads. Whole-server CPU budgets, derived from sampled JVM CPU load across
the ten reported hardware threads, were 4.298 versus 2.823 ms/commit for New
Order, 1.318 versus 1.380 for Payment, and 0.220 versus 0.103 for Order Status.
Those figures include JIT, GC, protocol, I/O adaptation and profiler work;
they are not isolated stage CPU times.

The profiles exposed repeated `IndexedHeadTableRootMap.nextTableAtOrAfter`
searches from catalogue name resolution and FK catalogue scans: 388 versus
five Java samples in New Order, 289 versus six in Payment, and 923 versus 22
in Order Status. A name scan ends at the first logical-head space with an
exclusive `Long.MIN_VALUE` key, so it includes no head row. The scalar scanner
nevertheless selected a mixed scan. After clustered storage removed table-row
head entries, the empty directory searched all 4,054 root slots repeatedly.

Commit `46e5ab39` corrects that interval intersection in the existing scalar
scan owner. Head rows start at key one; an exclusive upper key at or below one
does not enter their interval. The regression test checks empty and populated
directories, upper keys `Long.MIN_VALUE`, zero and one, and a crossing upper
key two that must still return the first head row. It and the descriptor row
path class passed. Final workload/profile results below verify this correction.

In the earlier New Order diagnostic, the declared-primary mapping compiler
ran 16,449 times and the added replacement-key protection ran 60,237 times,
all on virtual threads. Their JFR scope intervals contained six and 16 Java
execution samples respectively. Payment recorded 43,071 replacement-key calls
and seven scope samples, with **zero additional mapping compilations**: its
history table has no declared primary key. The JFR files and scope-event
instrumentation remain outside Git. Sampling is approximate and these scopes
exclude publication and shared framing; they do not establish an exact map
CPU total. They identify the map/locking work separately from the catalogue
search responsible for the observed large cost.

### Final virtual-thread CPU capture

The final diagnostic distribution starts from the frozen production JAR and
overlays only the six unchanged instrumented classes and the temporary probe.
The catalogue correction therefore executes in both final distributions.
`final-diagnostic-distribution.json` records that construction and its hash.
The scopes allocate JFR event objects; their throughput is diagnostic only.
The stable profiles use unchanged control classes with the same JFR settings.

| Family | Build | Whole-server CPU ms/commit, sampled budget | Catalogue head-search Java samples | Map scopes / scope samples | Replacement-lock scopes / scope samples |
| --- | --- | ---: | ---: | ---: | ---: |
| New Order | control | 2.823 | 5 | none | none |
| New Order | final | 10.133 | 0 | 10,158 / 8 | 37,178 / 20 |
| Payment | control | 1.380 | 6 | none | none |
| Payment | final | 1.699 | 0 | 0 / 0 | 53,126 / 13 |
| Order Status | control | 0.103 | 22 | none | none |
| Order Status | final | 0.107 | 0 | 0 / 0 | 0 / 0 |

All final map and replacement-key scopes execute on virtual threads. The
scope counts include attempted/rolled-back work in the measured window, so
division by commits alone is not an exact per-operation CPU measurement.
The control has no additional-map or replacement-scope instrumentation.

The large final New Order whole-server budget includes substantial continuing
JIT work: 52 recorded compilations overlapping the window totalled 10.822
compiler wall seconds, versus 15 and 2.423 seconds in its stable control.
The earlier candidate had 15 and 2.110 seconds. These are compilation events
above the profile's 100 ms threshold, not exact compiler CPU totals; durations
can overlap application execution. GC pauses were about 9.5 ms in the final
window. The measured budget cannot be assigned to mapping or locking, nor
can compiler wall time simply be subtracted from it. This concrete phase
difference prompted the longer uninstrumented New Order pair above.

The corrected final profiles contain zero samples in the excluded catalogue
head search, and Order Status's whole-server budget returns close to its
control. Exact read routing is established by the range test and source
owner; sampling alone does not prove every unobserved call count is zero.
Raw JFR, JSON event exports, CPU summaries, compiler summaries, timestamps and
scope definitions remain in `/private/tmp/erebor-write-cost/`. JFR CPU load
includes JIT, GC and recorder work; idle native poller samples are not treated
as isolated application CPU. These observations support the mechanism and
explain the limits of the CPU evidence without promising write speed.

## Slopmark review

The compact affected-module capture and targeted source captures are retained
outside Git. Isolated before/after captures use the same copied source scope
and report incomplete SHALLOW analysis:

| Owner | Before review fixes | Final |
| --- | ---: | ---: |
| Hybrid WAL sizing | 109.390 | 112.324 |
| Hybrid logical sizing | 39.424 | 95.313 |
| Relational mutation buffer | 91.909 | 81.031 |
| Overflow reclaimer | 21.596 | 28.512 |
| Retirement queue | absent | 26.027 |
| Frame cache | 262.889 | 262.889 |

The logical-sizing increase triggered a responsibility check. It remains the
single admission-budget owner for descriptors, logical mutations, payload and
WAL; reclamation records consume those existing budgets. Overflow demand is
computed once across the journal using the tuple compiler's shared placement
rule. The reclaimer owns bounded candidate eligibility, and the queue owns
durable linking/unlinking. No metrics, benchmark semantics, retry policy or
second allocator entered production. The frame cache did not gain another
responsibility. These scores are review signals with partial coverage, not
an approval or a numeric gate.

## Decision

R1–R4 are addressed, physical costs are visible, warmed read allocation is
zero in the tested paths, and the final clean integration check passes.
Named individual reports and the longer New Order pair pass. The targeted
ten-retry mix passes with reconciled retries and cleanup. The required
three-retry mix remains unsuccessful on both builds and needs owner/review
resolution. Updated independent durable-format/recovery/concurrency approval
is also required. No merge, checkpoint tag or new baseline is designated.
