---
id: tic-boromir
status: closed
type: story
priority: 1
assignee: blater
parent: tic-isildur
delivery: evidence
tags:
    - performance
    - storage
created: 2026-09-29T09:14:16.615802Z
---
# Replace linear metadata frame searches with bounded indexed lookup

Replace resident-frame array searches in `IndexedVersionDirectory` and
`IndexedRowDirectory` with a bounded page-to-frame lookup. Reduce CPU spent
finding already resident metadata during real indexed reads, while preserving
cache capacity, eviction policy, persistence and failure behavior.

### Evidence and hypothesis

[Tic-thranduil](tic-thranduil.md) and its
[indexed-probe evidence](../delivery/evidence/2026-09-29-tic-thranduil-indexed-probes.md)
establish that full Stock Level has a resident metadata working set after
warmup. The final diagnostic interval counted:

- 11,748,991 version-frame hits, plus the same number of separate one-record
  cache hits; the latter bypass the frame search.
- 11,748,991 row-location frame hits.
- 52 version frames and 15 row-location frames touched, within each directory's
  64-frame cache; zero measured frame misses, file reads or dirty evictions.
- 25,009 transactions in the counter interval: approximately 470 version-frame
  lookups and 470 location-frame lookups per transaction.

Source inspected at `2ada6350` shows that both `frame(...)` methods find a hit
by scanning the frame array from its first slot. A hit stops when its matching
slot is found; it does not necessarily examine all 64 slots. The actual mean
and distribution of entries examined were not measured by Thranduil.

The production profile placed 12 of 909 worker top-frame samples in
`IndexedVersionDirectory.frame` and nine in `IndexedRowDirectory.frame`.
Those samples motivate a bounded experiment but do not establish exact CPU
cost, expected speedup, or that this mechanism explains the MariaDB gap.

Hypothesis: indexing resident metadata frames lowers lookup work and CPU on
the unchanged full Stock Level workload without changing its row work or
metadata file activity. A faster isolated lookup alone is insufficient for
accepting this as an end-to-end performance improvement.

### Scope lock and scheduling

- **Observable outcome:** lower metadata hit-lookup work and CPU, with a
  repeatable end-to-end benefit on full Stock Level and no unexplained repeated
  regression in the selected write/mixed controls.
- **Canonical mechanism and owner:** resident metadata page identity to frame
  slot mapping, owned by the two engine table directories. Each directory owns
  its mapping instance and existing frame lifecycle.
- **Maximum change shape:** the two directory owners, at most one package-local
  primitive mapping helper shared by these immediate consumers, focused tests,
  and ticket/checkpoint evidence. No other production mechanism is included.
- **Non-goals:** increasing the 64-frame caches, changing LRU victim selection,
  optimizing `IndexedPageFrameMap`, altering the one-record version cache,
  removing repeated version reads, changing tuple comparison or SQL rechecks,
  changing retention/decoding, and primary-row layout work. SQL, schema, index
  inventory, WAL and durable directory formats remain fixed for this experiment.
- **Scheduling:** this is the next bounded implementation experiment under
  [tic-isildur](tic-isildur.md), ahead of [tic-erebor](tic-erebor.md). Erebor is
  related architectural work, not a dependency. The paused configurable-frame
  branch and its mixed-workload capacity question remain separate.

## Design

1. Replace both successful resident-frame array searches with an allocation-free
   primitive lookup. A fixed-capacity open-addressed map is a candidate; choose
   the simplest measured design with expected constant-time hits and a bounded
   worst-case probe sequence. Delete the superseded hit-search loops; there is
   no scan fallback or production switch between implementations.
2. Size lookup storage from the admitted frame budget, allowing an empty slot
   at maximum occupancy if the design needs one. Document the byte overhead,
   occupancy invariant, termination bound and status behavior at capacity.
   Replacement after the cache fills must evict through the existing policy,
   rather than reject an otherwise admissible directory page. Keep all storage
   bounded by resident frames, not the highest row ID or file offset.
3. Preserve the existing long page/offset identity domain. A normalized page
   ordinal is acceptable if its conversion is exact and owned by the directory.
   Handle page ordinal zero, large sparse row IDs and slot zero unambiguously.
   Do not truncate the row-directory byte offset into an integer key or reuse
   an existing int-key map without proving its domain fits.
4. Preserve recency updates, victim selection, dirty writeback, EOF zero-fill,
   record semantics and the version directory's one-record cache. The existing
   `findFrame()` victim scan is a miss-path policy and stays in scope only for
   integrating mapping lifecycle. Flush/checkpoint enumeration may still scan
   frames; it is not a resident-hit search.
5. Define one mapping lifecycle at the directory boundary:
   - A published mapping names exactly one valid resident frame with matching
     page identity; a frame has at most one published page mapping.
   - On dirty-victim write failure or short write, keep the old frame and mapping
     usable and dirty; do not publish the requested page.
   - Remove the old mapping before overwriting the victim's identity or bytes.
     Publish the new mapping only after a successful page load and valid EOF
     zero-fill. On failed or partial failed read, leave no mapping to partial
     bytes or to the overwritten old contents; a retry must reload correctly.
   - Clear/reset and version vacuum invalidation remove every affected mapping.
     Preserve valid mappings for surviving frames and rebuild from an empty
     transient map on reopen. Cover failure of truncate as well as success.
   - Reuse the existing synchronization/ownership boundary. Do not introduce
     locks, a concurrent map, another cache authority or a durable mapping.
6. Use bounded temporary diagnostics to establish before/after work, and remove
   them from production unless an existing named consumer requires them. Do not
   time every hit or allocate diagnostic records in the lookup path.

### Focused correctness and allocation proof

Extend existing owners' tests, including
`IndexedDiskDirectoryEvictionTest` and `IndexedDiskDirectoryCapacityTest`, and
add helper tests only for material map behavior. Cover:

- First/last resident slots, page zero, repeated hits, absent pages, colliding
  keys, wraparound probe sequences and deletion from a collision chain.
- Filling all 64 frames, clean and dirty replacement on a 65-page working set,
  and repeated churn without stale mappings, lost entries or nontermination.
- Sparse high IDs, including the existing three-billionth-row case, without
  allocating by address or losing high offset bits.
- Failed/short dirty writeback, a partial failed read into a reused frame, and
  successful retry of both the old and requested pages as applicable. Assert
  returned contents/statuses, not just map size.
- Version clear and vacuum invalidation, successful/failed truncate, flush and
  reopen, including the interaction with the existing one-record cache.
- The existing relational checkpoint, WAL recovery and interrupted-vacuum
  tests relevant to these directories. No durable-format change is expected.

Prove lookup/insert/remove reuse their primitive storage with no steady-state
allocation, new row copies, boxing or per-operation buffer views. Use focused
allocation evidence and profiling; do not add source-token or signature gates.
Review failed eviction and invalidation independently before promotion because
incorrect transient mappings can return wrong durable row/version contents.

Start with the narrow tests, using the repository build rules:

```sh
./gradlew --no-daemon :river-engine:test \
  --tests io.riverdb.engine.table.IndexedDiskDirectoryEvictionTest \
  --tests io.riverdb.engine.table.IndexedDiskDirectoryCapacityTest
```

### Performance experiment

1. Start from the latest pushed, tagged stable integration point. Record exact
   source, executable/version, harness revision, JVM, isolation, durability,
   host conditions and workload configuration. Capture the affected production
   module's slopmark baseline. Keep cache size at 64 frames of 64 KiB throughout.
2. In a separate diagnostic pass, measure each directory's frame lookups, hits,
   misses, identity comparisons/probes, occupancy, file reads/bytes and dirty
   evictions. Report totals and per-transaction/per-frame-lookup values, with
   version one-record hits counted separately. Measure actual baseline scan
   lengths; do not infer them from capacity or assume a uniform access pattern.
3. Compare lookup work with the same access pattern before and after. Include
   repeated-page, distributed resident-page and over-capacity access to expose
   hash-collision or replacement costs. A small isolated experiment supports
   mechanism attribution; it does not substitute for the real SQL workload.
4. Run at least two ordinary control and two candidate samples, interleaved
   A/B/A/B, using unchanged `full stock-level`, one worker/warehouse, seed 42,
   retry limit 3, five-second warmup and 20-second measurement. For example:

   ```sh
   ~/src/ingres/river-harness/benchmark run river tpcc full stock-level \
     --river-executable=/absolute/path/to/installed/river \
     --river-version=boromir-control-a1 \
     --warmup=5s --duration=20s --workers=1 --warehouses=1 \
     --seed=42 --max-retries=3
   ```

   Use distinct control/candidate and sample labels. Run serially on a quiet
   host with no overlapping builds or other workloads. Repeat anomalous low
   results, retaining every artifact and the reason for any exclusion. Lengthen
   matched interleaved runs when warmup effects or variation obscure the result.
5. Collect matched production CPU profiles separately from ordinary TPS runs.
   Report server CPU per committed transaction and worker attribution to
   directory lookup; distinguish compiler/profiler activity. Use profiling that
   captures the virtual-thread worker; the unsuccessful Thranduil
   `ThreadMXBean` timing pass is not usable evidence. Require unchanged candidate
   row work and explain any unexpected change in misses, file traffic or retries.
6. Use matched short `sample new-order` controls/candidates for dirty writes and
   `sample all` for integration. Start with one worker for New Order and four
   for the mix, fixed seed/warehouse/retries and identical windows per pair.
   Expand to `full all` only if miss/replacement behavior or a repeated regression
   needs resolution; do not merge the separate cache-capacity experiment here.

Report TPS, latency, CPU, retries/outcomes, invariants, cleanup, mapping memory
and probe counts together. Require passed runs, zero failed/unknown outcomes,
and eligible matching manifests where the harness supplies comparison metadata.
River control/candidate evidence suffices for this ticket; a MariaDB claim
requires the separate matched inventory and longer interleaved comparison rules.

## Acceptance Criteria

- Both directory hit paths use the selected bounded mapping, the old hit-search
  loops are removed, and lifecycle/failure tests pass without changing eviction
  policy, cache capacity or durable bytes.
- Measured lookup work and CPU improve, and repeated unchanged full Stock Level
  runs establish end-to-end benefit. A microbenchmark win, fewer source lines
  or the absence of file misses does not establish delivery success.
- If attribution is negligible or ordinary workload results remain inconclusive
  after a targeted longer pair, retain the evidence and close with a no-change
  decision rather than merging an unproven performance feature. Update delivery
  metadata to evidence if no production code is accepted. Do not broaden into
  tuple comparison, SQL rechecks or Erebor to obtain a gain.
- Stop for stale mappings, status/cleanup failures, new allocations, unexpected
  miss/writeback changes or an unexplained repeated TPS/latency regression.
  A required correctness prerequisite becomes its own dependency.
- Focused tests, affected-module checks, independent lifecycle/recovery review
  and the clean full checkpoint build pass before promotion. Follow
  [AGENTS.md](../../AGENTS.md) for serialized builds/workloads and performance
  checkpoints; no additional platform matrix or ADR is required for this local
  transient lookup change.
- Record source/version, exact commands, individual artifacts, CPU/mechanism
  evidence, correctness, slopmark comparison and the accept/reject decision in
  [performance-checkpoints.md](../performance-checkpoints.md). An accepted code
  delivery records its merge commit and pushed annotated performance checkpoint
  tag before closure; designate a new baseline only with the required table row.

## 2026-09-29 outcome

A bounded primitive map candidate passed focused eviction, capacity, failure,
vacuum, recovery and allocation tests, plus a clean full build in an isolated
worktree. The initial matched full Stock Level pair favored the candidate, but
competing performance tests were active. After those tests ended, a fresh
A/B/A/B sequence and a longer reverse-order pair all passed correctness while
each adjacent comparison favored the control. Candidate p99 latency was also
higher in every quiet comparison. The user had allowed one good run to pass
while host contention was present; the later quiet runs supplied the repeated
regression evidence required by this ticket's stop rule.

The candidate and its tests were removed from the production checkout. No code
or on-disk format was delivered, no new TPS baseline was designated, and no
performance tag was created. The exact commands, versions, artifacts,
validation, slopmark scores and limits of the evidence are in the
[performance checkpoint](../performance-checkpoints.md#2026-09-29--tic-boromir-resident-frame-lookup).
