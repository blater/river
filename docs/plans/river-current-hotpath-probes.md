# Two bounded River hot-path probes

## Evidence and scope

The 2026-09-27 committed `master` snapshot `c9c216d3` ran through the external
harness at `7d91f4f`, with one worker, one warehouse, seed 42, three retries,
20 seconds of warmup and 30 seconds measured. New Order passed at 346.27 River
commits/s and 904.97 MariaDB commits/s; Payment passed at 2,035.09 and
4,481.79 commits/s respectively. Both target pairs had eligible, identical
comparison keys within each workload, zero retries/failures/unknown commits,
and successful invariants. River used TCP/TLS and MariaDB a Unix socket, so
these are complete target measurements, not an isolated engine comparison.

The separately profiled River New Order run passed at 345.87 commits/s. Of 669
Java execution samples inside its measured window, 34 ended in
`BTreePage.childForKey` and 28 in `TupleKeyCodec.compare`. These counts identify
work to test, not the expected percentage throughput gain. The page-frame map
is already an open-addressed hash table. The first binary-routing trial was
held after adverse CPU observations. A later fixed-harness recheck showed a
small favorable CPU signal but no established throughput gain. Count current
internal-node occupancy and separator comparisons before promoting routing.

Source reports: `river_harness_20260927_070936_84c40fe8`,
`river_harness_20260927_071035_0bdc6477`,
`river_harness_20260927_071239_c74a6261`,
`river_harness_20260927_071504_9a5cd413`, and
`river_harness_20260927_071602_d827c587`. The JFR recording and timestamped
summary script are under `/private/tmp/river-perf-investigation/`.

## Probe 1: scalar internal-node routing

Change only `BTreePage.childForKey` from a linear separator walk to an upper-bound
binary search. Keep the existing child layout and route equality to the right
child. Use the already reviewed `tic-waymeet` mechanism as the starting point.
Test empty, first, middle, equal, last, and full-node cases, including differing
key spaces, split/recovery behavior and allocation-free traversal. Run focused
storage and engine tests before the same installed-server New Order controls.
Use occupancy, comparison counts and current-build CPU/JFR evidence to
distinguish long scans from frequent short scans.

## Probe 2: tuple-key comparison

Change only `TupleKeyCodec.compare` to compare eight bytes per iteration, then
handle the remaining bytes. A fixed big-endian byte-buffer view can read a
canonical word independently of each input buffer's order; compare words as
unsigned values. Do not change buffer order, position or limit or create a
duplicate.
Keep the public method's offset/length and length-prefix behavior; allocate
nothing. Test every mismatch position, equal prefixes of unequal lengths,
unaligned offsets, heap/direct/read-only/sliced buffers, both byte orders,
unchanged buffer state, and parity with the old byte loop over varied lengths.
Run focused format/storage/engine tests, then the same New Order controls.

Each probe has a separate source change and workload label. Measure each against
the same frozen runtime with the other probe absent, and preserve every sample,
including adverse ones. If repeated latency or throughput moves outside adjacent
variation, investigate before accepting. Do not combine the changes or claim a
cross-database speedup from a code-level sample alone. Verify that the same
physical connection serves warmup and measured phases; a previous harness
version replaced that connection and confounded short measurements.

## Outcome

Both code probes and their correctness tests were implemented by separate
agents and independently reviewed. The full ordered workload results and
artifact identifiers are in the [2026-09-27 diagnostic](../performance-checkpoints.md#2026-09-27--current-hot-path-investigation).
Routing reduced algorithmic search work but gave conflicting 30-second and
60-second throughput signals. Both tuple-word variants had adverse samples
amid a large decline in unchanged-control throughput. Neither change is an
accepted performance checkpoint or a supported explanation of the whole
River/MariaDB gap. Preserve the candidate diffs and address the existing
CPU-variation investigation before a merge decision.
