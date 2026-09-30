---
id: tic-uruk-hai
status: open
type: story
priority: 1
assignee: blater
parent: tic-isildur
delivery: code
tags:
    - performance
    - storage
    - refactor
created: 2026-09-29T09:23:21.523962Z
---
# Simplify indexed-row resolution and tuple-bound rechecks

Scheduling reconciliation, 2026-09-30: [Erebor](tic-erebor.md)'s clustered-row
replacement is delivered at `fdfda831`, checkpoint
`perf-checkpoint-20260929-tic-erebor-clustered-row-store`. Before implementation,
reassess the scope below against that accepted layout: consolidate surviving
snapshot-resolution responsibilities and use the direct comparator only for
row checks that remain necessary. Ordinary committed rows delivered with their
keys from the same visible primary leaf no longer need the separate row access
and recheck. Do not restore that removed path to implement this cleanup.

The source findings and implementation proposal below describe `2ada6350`,
before Erebor, and remain historical input to that reassessment rather than a
verified description of current callers. The direct ordering analysis remains
relevant to changed-row residual checks; verify their owners and lifetimes on
the implementation base before selecting the remaining change.

Simplify committed row reads around one explicit, reusable version-resolution
result. Point reads and scans should consume the metadata already selected by
resolution, observe the required commit dependency, and fetch a live row once.
Remove duplicate resolution code and repeated metadata lookups while preserving
snapshot, deletion, locking and row-lifetime behavior.

The same indexed-row delivery must also simplify the SQL tuple-bound recheck:
`RelationalDescriptorIndexCursor` currently encodes a fetched row's full key and
compares both bounds for every tuple candidate that reaches the recheck. Build a
direct comparison of the fetched row's required fields against prepared bounds,
without building another tuple key. The row eligibility check remains necessary
because the tuple scan returns only a logical row ID, while the fetched current
or pending row may have a different key.

The primary outcome is a cohesive indexed-read process that can be understood
locally: select the visible row once, then decide its tuple-bound eligibility
once from the smallest required set of row key fields.
The performance outcome is less work per resolved row, measured separately from
the cache lookup change in [tic-boromir](tic-boromir.md) and the durable layout
change considered by [tic-erebor](tic-erebor.md). No magnitude of TPS improvement
is assumed.

### Source findings

At `2ada6350`:

- `IndexedKernelVisibility.resolve` returns only `StatusCode` and communicates
  the selected row through separate mutable `resolvedRowId` and `version`
  fields. Its no-visible-version outcome relies on callers interpreting the
  row ID rather than a complete result contract.
- `fetchResolved` rereads the selected version into the same `version` object.
  Both `nextHeadEntry` and `nextEntry` also repeat this lookup after resolution.
  The selected metadata is already available when `resolve` succeeds.
- Point reads and the two scan paths separately perform closely related
  visibility, deletion, commit-observation and row-fetch steps.
- `IndexedVersionVisibility` contains another snapshot chain traversal; a
  repository-wide Java reference search found no callers. Verify this again
  on the implementation base before deleting or consolidating it.
- `fetchCurrentSuccessor` has different semantics: it verifies ancestry after
  protection, observes visited commits, and returns the current head row.
  `prepareMutation` checks current-head eligibility. Neither is an ordinary
  snapshot selection operation.
- `IndexedKernelRowAccess.fetch` owns location lookup, heap pinning, retention
  and release. Its retained copy gives row bytes a lifetime beyond the pin.
- `RelationalDescriptorIndexCursor` owns scan-bound encoding and a separate
  `recheck` that re-encodes each row reaching it into a complete tuple key, then
  compares the requested lower and upper prefixes. `RelationalDescriptorScanAccess.next`
  and `RelationalDescriptorCurrentRow.lockScan` call it after fetching a row.
  The recheck's `StatusCode` plus mutable `matches` field require callers to
  interpret two outputs. The tuple scan result currently carries only a logical
  row ID, so the cursor cannot infer from that result alone that the fetched
  row still matches the emitted index key.

[Thranduil's evidence](../delivery/evidence/2026-09-29-tic-thranduil-indexed-probes.md)
counted equal numbers of version-frame and one-record cache hits. This is
consistent with the reread, but does not establish its isolated CPU cost.
Removing one-record hits must not be reported as removing the same number of
frame searches or file reads.

The existing `RelationalTupleKeyEncoder.encodeUser` accepts a prefix length,
which could reduce encoding for partial bounds but would retain full-key work
for a full-key bound. The current bound values are borrowed only during cursor
preparation, while the encoded bounds survive for the B-tree cursor. A direct
comparison must own any bound values it needs beyond preparation.

### Ordering analysis, 2026-09-29

**Conclusion:** direct comparison can reproduce the sign and equality of the
current encoded `TupleKeyCodec.comparePrefix` for every *admitted value with the
same key descriptor*. This is a source-level derivation, not a test result or a
performance claim. The implementation still needs focused tests to catch coding
errors and workload measurement to decide whether the extra bound preparation
cost is justified.

`RelationalTupleKeyValidation` requires each bound field's full descriptor to
equal the corresponding `KeyDescriptor` field. A selected row is read under that
table/key descriptor, and `RelationalDescriptorScanCursor.prepareSelection`
includes key columns in a projected row. Thus the compared parts have the same
type, decimal scale and text declaration. Cross-type SQL coercion and
cross-scale decimal comparison are outside this recheck; using them here would
give a different order. The key encoder emits a constant type ID for each
corresponding part, then a null marker (`0`) or present marker (`1`). For two
admitted values of the same descriptor, the type IDs cancel and null sorts
before present. A direct comparator must do the same.

| Admitted part | Encoded unsigned-byte order | Equivalent direct comparison |
| --- | --- | --- |
| `SMALLINT`, `INTEGER`, `BIGINT`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMP_WITH_TIME_ZONE` | Big-endian `value ^ Long.MIN_VALUE`; the type's admitted domain is a subset of signed `long`. | `Long.compare` on the stored value lanes. Do not reinterpret temporal values or use locale/time-zone conversion. |
| Compact `DECIMAL` | Sign-extended high long with its sign bit flipped, then unscaled low long. | `Long.compare` on unscaled values because both descriptors have the same scale. This agrees with `ExactDecimal.compare` on equal scales. |
| Wide `DECIMAL` | High long with its sign bit flipped, then low long, both big-endian. | Signed high comparison followed by unsigned low comparison. This is `ExactDecimal128Math.compareSigned`, used by `ExactDecimal128.compare` on equal scales. |
| `REAL`, `DOUBLE` | IEEE bits transformed by `SqlApproximateNumeric.sortableBits`; `REAL` occupies the low four bytes of an eight-byte lane. | Numeric comparison of the admitted finite values at the *same* type. `REAL` to `double` is exact; `DOUBLE` remains `double`. NaN and infinities are rejected at admission; negative zero is canonicalized to positive zero. Raw signed-bit or ordinary `Long.compare` is **not** equivalent. |
| `VARCHAR` | Each valid Unicode scalar becomes big-endian `scalar + 1`, followed by a zero terminator. | Unsigned lexicographic comparison of canonical UTF-8 bytes, including shorter-prefix ordering. `Utf8Text.compare` supplies this order. No collation, case folding, normalization or UTF-16 code-unit comparison. |

`TupleKeyCodec.comparePrefix` skips the header and compares only the requested
number of whole encoded parts lexicographically, stopping at the first unequal
part. A direct comparator that uses the table above and stops at the first
unequal part therefore has the same sign and equality for `lowerParts` and
`upperParts` independently. The physical logical-row-ID suffix does not take
part. Existing inclusive/exclusive decisions, scan direction, empty-range and
exact-unique checks can continue to use their current encoded bounds.

The proof requires two ownership conditions. First, encode and admit external
bound values during `prepare`, then copy the needed primitive lanes, null flags
and canonical UTF-8 bytes into cursor-owned storage before the borrowed input
expires; keep the encoded bounds for B-tree search. Second, compare only an
admitted selected row with projected key columns. Rows already admitted by
River need no new per-row UTF-8 or value-domain scan under the storage-trust
policy in [AGENTS.md](../../AGENTS.md); new external or computed values retain
validation at their admission boundary. If either condition fails in an actual
caller, fix that boundary before replacing the recheck.

The existing key-descriptor admission limit is 32 parts and at most 3,072
encoded user-key bytes. For a validated bound, raw UTF-8 occupies no more bytes
than its scalar encoding, so an owned raw-text copy needs at most 3,072 bytes
per side, plus fixed primitive/null arrays. Allocate or reuse that bounded
storage on preparation, report resource exhaustion through the existing status
contract, and measure the extra retained cursor bytes and copies. This is a
setup/lifetime cost, even though it removes row-key encoding from each recheck.

Proof sources: [tuple part encoding](../../river-format/src/main/java/io/riverdb/format/btree/TupleKeyBuilder.java),
[prefix comparison](../../river-format/src/main/java/io/riverdb/format/btree/TupleKeyCodec.java),
[bound descriptor validation](../../river-engine/src/main/java/io/riverdb/engine/relational/RelationalTupleKeyValidation.java),
[approximate numeric admission/order](../../river-base/src/main/java/io/riverdb/base/type/SqlApproximateNumeric.java),
[UTF-8 comparison](../../river-base/src/main/java/io/riverdb/base/text/Utf8Text.java),
and [key-size admission](../../river-engine/src/main/java/io/riverdb/engine/schema/KeyDescriptorPublication.java).

This settles the ordering question by analysis. Remaining investigation is
limited to checking bound-copy lifetime and capacity in the implementation,
focused edge-case tests for implementation mistakes, and measuring setup cost
against eliminated per-row encoding. No exhaustive equivalence test matrix is
required to establish the ordering rule.

### Scope lock and scheduling

- **Observable outcome:** one explicit snapshot-resolution contract used by
  point and scan reads; no second metadata lookup just to consume a selected
  version; implement the direct typed bound comparison established by the
  ordering analysis and retain it if the measured real path benefits; preserve
  SQL results, status and durability behavior.
- **Owners:** the engine table visibility boundary owns snapshot selection; the
  relational descriptor scan boundary owns tuple-bound eligibility. Storage
  metadata lookup, heap-byte ownership and session isolation policy retain
  their existing owners.
- **Maximum change shape:** consolidate the existing visibility implementation,
  its reusable metadata/result carriers and immediate point/scan callers; remove
  the unused alternative resolver; simplify `RelationalDescriptorIndexCursor`,
  its two recheck callers and selection only if needed for a chosen comparator.
  At most one local reusable bound/comparison owner may be added. Add focused
  tests and evidence.
  Use at most one concrete snapshot resolver and one resolved-version result
  type, preferably by adapting existing code. No new executor, second scan
  path, interface hierarchy, callback framework or cache is included.
- **Non-goals:** frame indexing/capacity, changing tuple ordering/comparison
  semantics, skipping bound rechecks by assuming a scanned key equals the
  selected row's key, tuple scan provenance or result changes, storage-layout
  changes, removing retention copies, changing lock/isolation or durability
  rules, scan-order redesign and broad cleanup of forwarding layers.
- **Scheduling:** after Erebor's layout decision in the current queue. Scope
  the cleanup against the accepted or retained architecture, and record which
  old responsibilities disappeared with that change. These are separate
  measured deliveries, not dependency edges. Rebase and measure against the
  actual accepted integration point; do not fold the two gains into one claim.

## Design

### One explicit resolution result

1. The resolver accepts a head version identity and visible commit sequence and
   walks the chain until the first version at or before that sequence. Make the
   selected identity, commit sequence and deletion state available together in
   one reusable result. Define absence without overlapping booleans, sentinel
   fields and enums describing the same state. Keep metadata lookup availability
   distinct from a successfully selected snapshot version where necessary.
2. Reset the selection for every invocation, including a missing head, exhausted
   chain and error. A previous successful read must not supply the next read's
   identity or bytes. State explicitly which fields remain meaningful after
   each status; preserve any already established durability dependency separately
   from the availability of a returned row.
3. Perform one logical `IndexedVersionState.lookup` per version visited by the
   snapshot traversal. Consume that result directly after selection. Directory
   overrides and checkpoint-base lookup remain inside `IndexedVersionState`;
   their existing fallback is part of storage ownership, not duplicate snapshot
   resolution to remove.
4. A visible deletion is a selected version that makes the row absent. Never
   continue past it to return an older live row. Its commit sequence remains
   available for dependency observation even though no heap row is returned.
5. Own the result within the existing synchronized/kernel lifetime or the caller
   that needs it. No per-row allocation, `Optional`, boxing, captured lambda,
   new lock or globally shared scratch state. Define when another resolution
   invalidates it; copy primitive fields into existing caller-owned outcomes
   before reuse when they must survive another operation.

### Cohesive point and scan reads

The read sequence should be visible directly in the code:

```text
find head -> resolve visible version -> observe commit dependency
          -> fetch live row -> publish caller result
```

Use one implementation of snapshot selection and share the finishing work that
has identical meaning. Point access maps an absent/deleted selection to its
existing result status; a scan advances past it. Keep that distinction at the
caller rather than encoding it as mode flags in a generic executor.

- Remove the redundant lookup in `fetchResolved`, `nextHeadEntry` and
  `nextEntry`. Fetch a selected live row through the existing row-access owner
  at most once per candidate. Publish a row only after a successful fetch.
- Keep scalar-tree traversal, logical-head traversal, mixed-scan merging and
  cursor advancement with their current owners. Consolidate the common
  candidate-resolution/consumption logic; do not combine different traversals
  merely to reduce line count.
- Make commit observation ordering explicit before early returns or scan skips.
  Preserve accumulation across invisible/deleted candidates as required by the
  existing contract and across mixed-scan branches. Do not zero an established
  dependency because the final row result is absent or a later fetch fails.
- Preserve retained-row ownership and independent mixed/nested scan lookahead.
  The resolved metadata result must not borrow a page buffer or extend a pin.
  `IndexedKernelRowAccess` remains responsible for retention and release.
- Keep locked-successor ancestry verification and mutation-head checks as
  clearly named operations. They may use the same low-level metadata carrier,
  but must retain their distinct rules. In particular, reaching an older
  candidate in the chain must still fetch the current head for a successor
  read, and a deletion or missing ancestry must retain its existing outcome.

### Tuple-bound decision after row selection

1. Keep the index's canonical encoded bytes for durable entries and B-tree
   search. At cursor preparation, copy the bound values needed for later row
   rechecks into cursor-owned, bounded, reusable storage. The input
   `SqlValueAccess` is borrowed; never retain it beyond preparation. Reuse
   primitive fields for numeric values and bounded owned bytes for text. Count
   setup copies and memory, including two-sided bounds.
2. For each fetched row, compare only the first `lowerParts` and `upperParts`
   key fields with the prepared bounds, lexicographically, and apply each
   side's inclusivity. Use the row's `SqlValueAccess` directly; do not encode
   a candidate tuple, allocate per row or copy row text. An unbounded scan
   matches without reading index key fields.
3. Implement the order derived in the analysis above: same-descriptor null
   marker, signed fixed values, signed-high/unsigned-low wide decimal, admitted
   finite approximate numerics and unsigned canonical UTF-8 bytes. Compare
   exactly each side's prefix length. Do not use cross-type numeric coercion,
   raw IEEE bit comparison or UTF-16 string comparison. Keep one ordering
   policy; if implementation inspection contradicts an assumption in the
   derivation, resolve that contradiction before replacing the encoded path.
4. Preserve the row eligibility recheck for bounded scans, including committed
   and pending candidates and a row locked after scanning. The tuple scan
   currently emits only a logical row ID. It gives no cheap proof that the
   scanned entry's key equals the selected row's key, so this ticket does not
   attempt a blanket skip. Carrying version/key provenance through the cursor
   would need a separate measured design and correctness review.
5. Give `recheck` one clear outcome for match, non-match and error. Prefer the
   existing `StatusCode.CONFLICT` for an ordinary non-match if both callers can
   use it without changing the surrounding control flow; remove `matches`
   mutable state when it has no separate lifetime purpose. A scan skips a
   non-matching candidate; a locked current-row operation releases its lock
   before returning the existing conflict outcome. Avoid a new overlapping
   status/result taxonomy.
6. Keep bounds preparation, prefix-shape reuse, exact-unique admission,
   inclusive/exclusive endpoints, scan direction and empty-range behavior in
   their current owner. Remove state only when it no longer serves those
   contracts. The cursor remains focused on index-bound state and eligibility;
   version selection stays with the engine table owner.
7. Build the direct comparator as one isolated candidate. Ordering equivalence
   is established above under the stated admission and ownership conditions.
   If those conditions fail in a real caller or measured setup/row costs do not
   improve the selected workloads, discard it and retain the existing encoded
   recheck. No permanent dual implementation, runtime flag or
   benchmark-specific numeric shortcut remains. Record the result without
   weakening the version-resolution cleanup.

### Outcome and dependency contract

Document the internal selection outcomes next to their owner and cover the
following behavior through existing callers. Continue using `StatusCode` for
expected outcomes; no new overlapping outcome taxonomy is required.

| Condition | Required behavior |
| --- | --- |
| Head is visible and live | Select it, observe its commit and fetch it once. |
| Head is too new, older live version is visible | Select the older version, retaining the existing snapshot dependency rule. |
| First visible version is deleted | Return/skip absence, preserve its commit dependency, fetch no heap row. |
| No head or every version is too new | Return/skip absence without stale selection; preserve the owning read contract's dependency semantics. |
| Metadata or heap access fails | Propagate the existing status, expose no successful row, retain required observed dependency and release owned pins. |
| Scan skips candidates or merges branches | Keep ordered results and the existing maximum observed commit across consumed decisions. |
| Locked successor verifies ancestry | Preserve maximum observed commit across visited versions and return the current row only when the ancestry rule succeeds. |

Use [tic-e544](tic-e544.md) and the existing durability tests as the authority
for observation semantics. Snapshot selection, current-head eligibility and
successor ancestry must not accidentally inherit each other's commit rules.
If inspection reveals a correctness gap beyond consolidation, record it as a
separate prerequisite rather than silently altering the isolation contract.

### Remove obsolete code and lower cognitive load

- Remove `IndexedVersionVisibility` if the canonical implementation remains in
  `IndexedKernelVisibility`. If it becomes the canonical owner, replace its
  current contract and remove the competing implementation in the same delivery.
  No unused alternate resolver or migration wrapper remains.
- Replace the implicit `resolvedRowId`/metadata handoff with the selected result
  contract and delete superseded fields, helpers and tests. Keep genuinely
  different low-level lookup and caller row-result roles distinct.
- Avoid helpers that only rename a branch, result wrappers that copy every field
  without a lifetime purpose, and boolean parameters selecting read modes.
  Use names describing selection, observation, fetching and bound eligibility,
  with shallow flow.
- Comments explain visibility, dependency ordering and ownership. Do not add
  runtime content validation, diagnostics that control reads, or a catalogue of
  method signatures to police the refactor.
- Reviewers must be able to identify where a version is selected, why a row is
  absent, where its dependency is observed and who owns returned bytes by reading
  the resolver contract and its immediate caller. Fewer files or lines alone
  do not establish a successful cleanup.

### Correctness and allocation validation

Extend focused tests through the real table/session and relational read paths:

1. Matching/missing keys, exact commit-sequence boundary, latest and older
   snapshots, multi-version chains and a snapshot before insertion. Cover a
   newest deletion with an older live snapshot and a visible deletion followed
   by reinsertion; never return a version through a visible tombstone.
2. Scalar and relational-head scans, including mixed scans across their boundary,
   skipped deletions, end-of-scan, nested scans and independent lookahead. Point
   and scan reads at the same snapshot must agree on row identity and contents.
3. Reuse after success, absence, deletion and injected failure, then another
   success. Check row/result availability, selected identity and observed commit
   to expose stale reusable state. Include heap failure after successful resolution.
4. READ COMMITTED statement refresh, REPEATABLE READ snapshots and SERIALIZABLE
   protection/refresh; own pending insert/update/delete and rollback continue
   through their existing session paths.
5. Current-successor ancestry success, missing ancestry and an intervening
   deletion, plus existing mutation conflict checks. Confirm returned current
   identity and required dependency, not only the returned values.
6. Durability dependency for live rows, deletions and missing results, scan
   accumulation, blocked WAL force and force failure. Retain existing status,
   cancellation/resource-pressure cleanup and pin-release behavior.
7. Relevant checkpoint-base, WAL reopen and vacuum tests to ensure both live
   directory metadata and checkpoint-backed metadata use the same contract.
8. Tuple-bound behavior for exact unique, partial prefix, open-ended and
   inclusive/exclusive range scans in both directions, including null and text
   suffixes. Cover committed/pending entries, key updates after scan opening,
   older snapshots, deleted rows, private index builds and the locked current
   row that moves outside the bounds. Prove both returned rows and excluded
   rows through the real SQL/relational path, with lock cleanup after rejection.
   Use a small set of representative direct-versus-encoded cases to guard the
   implementation: null/present, signed and decimal extrema, finite approximate
   values around zero, short/prefix and supplementary UTF-8, and differing
   lower/upper prefix lengths. This is regression coverage, not the ordering
   proof. Assert that borrowed bound input can change after preparation without
   changing the cursor's decision, and cover reuse after cursor reset.

Start with selected methods in `IndexedTableTest` and
`IndexedTransactionSessionTest`; include
`EmbeddedCatalogDurabilityOverlapTest` for read-dependency coverage. Add
`SqlDescriptorTupleIndexScanTest` cases for tuple-bound behavior where existing
tests do not prove the required boundaries. Run affected
engine checks and the required clean full checkpoint build with `--no-daemon`,
serially, following [AGENTS.md](../../AGENTS.md).

Confirm steady-state zero allocation for the new result handoff and no added
row copies or retained pins. Use runtime evidence and source ownership review;
do not use source-token gates or wall-clock assertions in unit tests.

### Measurement and review

- Capture slopmark before/after on touched production files as a review signal.
  The initial inspection scored `IndexedKernelVisibility` about 113.3, with a
  shallow-boundary coverage warning. This is context, not a numerical target or
  proof of bad design; review responsibilities and caller flow directly.
- In a bounded diagnostic pass, count chain versions visited, logical version
  lookups, one-record hits, frame hits/misses, selected live/deleted/absent rows,
  heap fetches, tuple-bound rechecks, row-key encodes avoided, compared parts,
  setup copies/memory and row bytes retained for recheck. Normalize by resolved
  candidate and transaction. The duplicate selected-version lookup must
  disappear; legitimate chain walking and bounded row-key checks must remain.
  Remove temporary instrumentation before ordinary performance samples.
- Compare at least two control and two candidate full Stock Level samples,
  interleaved, with identical SQL/schema/indexes, one worker/warehouse, seed 42,
  retry limit 3, five-second warmup and 20-second measurement. Use the installed
  executable through the external harness, with distinct source/version labels.
  Follow the command/configuration pattern in [tic-boromir](tic-boromir.md).
- Use short matched sample New Order runs as the adjacent mutation control.
  Repeat anomalous results and lengthen interleaved runs to explain repeated
  shifts. Broaden workload coverage only for a concrete unresolved concern.
  No concurrent builds or workloads; retain all outcomes and cleanup evidence.
- Record TPS, latency, CPU where available, allocations/copies, lookup counts,
  retries, correctness and cleanup. Do not attribute Boromir's frame-search gain
  or Erebor's possible layout gain to this refactor.
- Obtain independent correctness/concurrency review of selected-version state,
  deletion handling, dependency observation and result reuse before promotion.
  Do not approve those changes solely through the author's review.

## Acceptance Criteria

- One snapshot resolver and explicit reusable result replace the implicit
  handoff. Point reads and scans consume selected metadata without rereading it.
  All affected River-owned callers migrate in the same delivery.
- The common read sequence is cohesive and local; distinct traversal, successor
  and mutation rules remain explicit. Superseded code, unused resolver paths
  and redundant state are removed without introducing a framework.
- Tuple-bound eligibility has one clear outcome contract. If the direct
  comparator is accepted, it uses admitted cursor-owned bounds and compares
  fetched row values without re-encoding them, with proven equivalence to the
  canonical tuple-key order and no per-row allocation/copy. Required
  selected-row checks still run for bounded scans. Measure this reduction
  separately from version-lookup reduction. If the comparator is rejected,
  retain the encoded recheck and record why.
- Snapshot/deletion, dependency/status, mixed/nested scan, pending-write and
  failure/reuse tests pass; no new steady-state allocation, copies or pin leaks
  are introduced. Independent correctness/concurrency review is complete.
- Mechanism evidence shows removal of duplicate logical lookups. No unexplained
  repeated performance regression, failed invariant or cleanup failure remains.
  A throughput-neutral result may be accepted for the requested simplification
  with demonstrated reduced work and understood measurements; label it honestly
  as a cleanup with no established TPS gain. Do not claim a speedup from counts.
- Stop and rescope if the change needs cache, layout, executor, lock or durability
  policy redesign, or if result lifetime cannot be made safe within the existing
  ownership boundary. Do not add fallback code or change the benchmark to pass.
- Scope correction, 2026-09-29: the user added tuple-bound recheck cleanup
  before this ticket entered progress. The added relational owner and maximum
  change shape are explicit above; version resolution and tuple-bound reduction
  must be measured separately even if they are delivered together.
- Scope clarification, 2026-09-29: a cheap key-identity proof has not been
  established; the current tuple scan result contains only a logical row ID.
  The concrete experiment is direct comparison of selected row values against
  copied typed bounds; a provenance-based skip is outside this ticket.
- Scope correction, 2026-09-29: the user requested a no-encoding recheck
  alternative. The earlier prefix-only encoding proposal cannot remove row-key
  encoding for a full-key bound, so it is superseded here.
- Record the source, exact commands/configuration, individual artifacts, focused
  and clean-build results, review, slopmark assessment and decision in
  [performance-checkpoints.md](../performance-checkpoints.md). Follow the
  repository merge/tag/push checkpoint rules and record the delivered commit
  and checkpoint tag before closing the ticket.
