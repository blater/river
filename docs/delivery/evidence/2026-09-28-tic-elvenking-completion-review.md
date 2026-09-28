# Review of the completed trusted-values change

Recorded: 2026-09-28 14:53 UTC.
Previous review: [implementation intent and public-codec follow-up](2026-09-28-tic-elvenking-intent-review.md).
Delivered ticket: [tic-elvenking](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/docs/tickets/tic-elvenking.md>).

## Decision

**Both original P2 findings are resolved. The later recommendation to remove
or justify the unused public decoder is not resolved.** The completed change
removes repeated content validation from the reviewed stored-row paths, preserves
real input admission, and has passing correctness evidence. Keep those changes.

Record the public-codec cleanup as outstanding work. The implementation still
contains a full validator whose checked entry point has no production caller
in this repository. Its access-token arrangement also introduces an unnecessary
dependency from the row package back to the relational package. This is a
remaining architecture and scope issue; the inspected production readers bypass
that validator, so it is not evidence of a Stock Level regression.

A further performance/refactor opportunity is to make physical-column usage and
projection liveness share one owner. The new block-reader text-usage analysis
can still request text that downstream projection liveness discards.

No new correctness failure was established in this review. The measurements
support a diagnostic checkpoint, not a stable speedup or a revised MariaDB
comparison. No rollback is recommended on the evidence reviewed here.

## Source and review scope

The reviewed worktree was clean at `/private/tmp/river-trusted-values`:

- Production implementation: `7ac1246486dd7102732b208f6b91255ffd5ea1ef`.
- Evidence and ticket closure: `3572b295b23892261dcd5b9463a62eb911ab6f20`.
- Integration commit: `75039adc0461dd4b8f37f8b5b200032e2bac9936`.
- Annotated tag: `perf-checkpoint-20260928-trusted-stored-values`.

The base, storage and engine trees match between the production commit,
evidence commit and integration commit. Local remote-tracking references put
`origin/master` at the integration commit and the feature branch at the evidence
commit; this review did not contact the remote to independently verify push state.

This review covers source, existing test reports, final workload artifacts, ADR
alignment and the earlier findings. It started no build or workload and changed
no production code. Source links identify locations in the reviewed worktree;
the commits above identify the reviewed version if that worktree later changes.

The completed branch contains the original 12:58 review, but not its later
14:09 public-codec follow-up from the main workspace. Consequently, the delivery
record's statement that the two original findings were resolved is accurate;
it does not establish that the later architecture recommendation was addressed.

## Disposition of the earlier issues

| Earlier issue | Status | Evidence and implication |
| --- | --- | --- |
| Finding 1: full content validation on other trusted read/update paths | Resolved | All four named callers now use structural checks. Raw external admission remains semantic. |
| Finding 2: numeric-only descriptor reads inspect unused text metadata | Resolved | The decoder applies its filter before text preflight and does not walk text slots when `publishText=false`. |
| Follow-up: unused public codec admission, duplicated checks and access token | Open | The public checked overloads, `StoredTableRowExternalAdmission` and `RelationalStoredRowAccess` remain; only tests call the checked decoder. |
| Unchanged text is transcoded or re-admitted during updates | Resolved in reviewed paths | Descriptor updates use typed byte copies; the older SQL update path carries unchanged encoded text without full semantic validation. |
| Policy, tests and final candidate evidence | Substantially supplied | ADRs reflect storage trust, focused reports pass, and final-build samples exist. Their performance limits remain material. |

### Finding 1: trusted reads and updates

The exact callers named in the original review now call
`hasSafeStoredRowLayout()`:

- [SqlMutationRowEncoder.finishRow and copySourceRow](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/sql/SqlMutationRowEncoder.java#L266>).
- [RelationalIndexLookup.copyRow](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/relational/RelationalIndexLookup.java#L308>).
- [RelationalRowMutation.copyRow](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/relational/RelationalRowMutation.java#L298>).

[TableDefinitionRowCodec](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/relational/TableDefinitionRowCodec.java#L20>)
separates bounded layout access from full semantic admission. The structural
operation checks the fixed region and text ranges without UTF-8, numeric-domain,
padding or canonical-packing scans. It still walks variable-field metadata;
this finding's resolution does not imply projection-aware decoding everywhere.

[RelationalRowMutation](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/relational/RelationalRowMutation.java#L56>)
distinguishes raw rows from accepted SQL rows, then shares the same constraint,
insert/update and index-maintenance operations. The change does not introduce
a second mutation executor. Raw `RelationalSession` input still receives full
validation, and a focused SQL test rejects malformed raw text.

[SqlDescriptorMutationValues.buildUpdate](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/sql/SqlDescriptorMutationValues.java#L59>)
uses `copyTrusted()` for unchanged columns. Its assignment branches retain checks
for newly supplied or computed values. The copy preserves destination ownership
without converting unchanged UTF-8 to UTF-16 and back.

### Finding 2: numeric-only descriptor decoding

[StoredTableRowDecoder](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/row/StoredTableRowDecoder.java#L23>)
checks identity, the enclosing range and the fixed prefix. It then applies the
filter. Text metadata is visited only when text will be published.
[StoredTableRowBounds](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/row/StoredTableRowBounds.java#L17>)
checks the accessed text ranges and total destination requirement without
requiring contiguous text packing or exhaustive payload consumption.

[StoredTableRowCodecTest](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/test/java/io/riverdb/engine/row/StoredTableRowCodecTest.java#L63>)
specifically covers malformed UTF-8 and inaccessible text metadata being ignored
by numeric-only decoding. Other cases preserve rejection of required-access
bounds and identity errors. This directly addresses the original distinction
between safe access and exhaustive content inspection.

## Remaining findings and refactor recommendations

### R1 — P2: finish the public-codec boundary cleanup

[StoredTableRowCodec.decode](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/row/StoredTableRowCodec.java#L48>)
still performs full admission before delegating to the stored decoder.
[StoredTableRowExternalAdmission](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/row/StoredTableRowExternalAdmission.java#L18>)
checks every field, UTF-8, character limits, domains and canonical representation.
The subsequent decoder repeats header/range checks and, when publishing text,
walks text metadata again. Admission precedes filtering and destination-capacity
checks, including on calls with `publishText=false`.

The two production codec users, `RelationalDescriptorRowBuffer` and
`RelationalDescriptorRowValidation`, call `decodeStored()`. A repository-wide
Java caller search found the checked overloads used only in tests. No explicit
external consumer or supported encoded-row contract was identified. The
[access token](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/relational/RelationalStoredRowAccess.java#L3>)
exists to distinguish these internal callers from callers of the public method.

**Action now:** internalize the stored-row codec at its owning implementation
boundary and remove the unused admission overloads, validator and token. Adjust
package ownership and all callers/tests together. Preserve package-private
stored-value publication; do not replace the token with public unchecked setters
or a caller-controlled validation flag. Retain checks at actual SQL, protocol
and supported embedded-input boundaries.

If arbitrary encoded-row admission is genuinely a required external capability,
document its consumer and contract instead. Give it one admission owner and reuse
validated structural information. The mere existence of a public Java method
is not that requirement. Do not retain the validator as a deferred integrity tool.

This recommendation removes a responsibility with no identified production
consumer. It does not promise a throughput gain: the current hot callers already
avoid the full scan, and the validator/token objects are reused rather than
allocated for each row.

### R2 — P2 follow-up: share physical-column dependency and liveness analysis

[SqlBoundBlockPlans.physicalTextUsed](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/sql/SqlBoundBlockPlans.java#L91>)
scans names across every block. The same owner already holds
[SqlBlockProjectionLiveness](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/sql/SqlBlockProjectionLiveness.java#L14>),
which tracks transitive output usage. The physical text check does not use that
information. Later, [SqlBlockRowProjection](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/sql/SqlBlockRowProjection.java#L28>)
can discard the projection after the source reader has decoded its text.

For example, in the physical-table block path:

```sql
SELECT quantity
FROM (SELECT quantity, label FROM block_values) source;
```

The new name scan marks `label` as required because the inner block names it,
even though the outer query does not use it. This is a source-derived example,
not a workload executed by this review. It demonstrates unnecessary requested
work; it does not establish incorrect SQL results or a measured TPS penalty.

**Action:** extend the existing dependency/liveness owner to produce the physical
column set once at preparation time, using bound column identity and transitive
dependencies. Pass that prepared set to the decoder. Replace the separate name
scan rather than retaining two competing decisions about column usage. Cover
unused inner projections, aliases, predicates, ordering, grouping/aggregates and
SELECT-all behavior. Preserve columns required for semantics even if they are
absent from the final result. Keep analysis outside the per-row loop.

The current implementation already computes its text mask during preparation;
do not describe the name scan itself as a per-row cost. The remaining cost is
the unnecessary text decoding requested by an overly broad mask. This is a
useful next projection slice, not a reason to reopen the resolved descriptor
metadata finding or add another executor.

### R3 — P3: consolidate ownership without adding generic abstractions

- **Package direction:** R1 removes the row-to-relational token dependency.
  Also review the new relational-to-SQL dependency in
  [RelationalSession's accepted-row methods](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/relational/RelationalSession.java#L671>).
  `SqlAcceptedRow` has a real production consumer and binds the session/table,
  unlike the unused public decoder. Preserve that ownership guarantee while
  placing the admitted-row contract with its owning mutation boundary when
  refactoring. Do not introduce another adapter or duplicate write path.
- **Repeated structural checks:**
  [SqlResultTextLanes.setUtf8](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/sql/SqlResultTextLanes.java#L35>)
  checks the same source range that `Utf8TextArena.appendTrusted()` immediately
  checks again. Let the lowest owning copy operation return the status and let
  the result layer publish only on success. These are cheap checks; this is
  local ownership cleanup with no demonstrated performance benefit.
- **Remaining INSERT admission:**
  [SqlInsertRowEncoder.encode](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/river-engine/src/main/java/io/riverdb/engine/sql/SqlInsertRowEncoder.java#L51>)
  still ends with `table.isValidRow(row)`. Audit which invariants are first
  established there before removing anything. Its encoder copies new/default
  text, so this review does not establish that every final check is redundant.
  It is outside the resolved stored-read/unchanged-update findings.
- **Avoid indiscriminate DRY changes:** descriptor rows and `TableDefinition`
  rows have different layouts. Similar loops do not justify a universal validator.
  Preserve the useful `SqlValueBuffer` consolidation, shared mutation/constraint
  operations and the direct result-owned text copy. Replacing the remaining row
  representation requires its own complete caller migration; it is not needed
  for the narrow codec cleanup.

## Correctness and performance evidence

Existing reports for the following focused classes contain **48 tests, zero
failures, errors or skips**, timestamped 14:16:52–14:19:16 UTC:

| Test class | Tests |
| --- | ---: |
| `StoredTableRowCodecTest` | 12 |
| `SqlValueBufferTest` | 11 |
| `SqlTrustedStoredValueTest` | 3 |
| `SqlResultTextLanesTest` | 2 |
| `LocalTemporalStorageValidationTest` | 6 |
| `RelationalDescriptorRowPathTest` | 12 |
| `RelationalDescriptorStorageValidationTest` | 2 |

The SQL tests exercise unchanged multibyte/empty/null values, rollback, restart,
new assignment rejection, raw malformed-byte rejection and numeric/text nested
block scans. The result tests exercise trusted byte publication, bounds and
ownership after heap-source reuse. The allocation test passes, but its current
loop uses the public checked codec; after R1, retain allocation evidence through
the production stored-row entry path.

The [final checkpoint](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/docs/performance-checkpoints.md#L1350>)
records `./gradlew --no-daemon clean check` passing on `7ac12464` in 3m 21s,
156 tasks, plus an independent correctness/ownership review. This review read
that evidence and the focused XML reports; it did not rerun the full check.
The checkpoint's earlier zero-call instrumentation for UTF-8 validation and
unchanged-text conversion belongs to the initial candidate and should not be
represented as a fresh instrumentation run on the final commit.

All 14 final workload artifacts were inspected under
`/private/tmp/river-harness-stock-analyze/runs/`. They report passed status and
invariants, eligible comparisons, matching comparison keys within each of four
workload/window groups, and zero retries, failed transactions or unknown commits.
Measured cancellations are reported separately: thirteen runs have one and one
run has zero. Do not summarize these artifacts as having no cancellations.
The delivery checkpoint records graceful cleanup and inactive service afterward.

| Final-build workload | Control TPS | Candidate TPS |
| --- | --- | --- |
| Sample New Order, 5s warmup / 20s measured | 374.097; 201.496 | 355.346; 221.288 |
| Full Stock Level, 5s / 30s | 1,204.022; 1,130.057 | 789.543; 1,182.257 |
| Full Stock Level, 10s / 60s | 1,207.460 | 1,306.026 |
| Sample Stock Level, 5s / 30s | 6,984.172; 10,502.331 | 11,865.303; 7,671.413 |

The short runs fluctuate substantially in both directions. The longer final
comparison has only one pair; the two longer candidate samples from the initial
commit are not substitutes for repeated final-build samples. The evidence does
not establish a stable improvement or prove the absence of a smaller regression.
Keep the checkpoint's diagnostic qualification. Before claiming a speedup, use
longer interleaved samples on a quiet host against the exact final source.

[ADR 0004](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/docs/adr/0004-durable-identities-pages-and-rows.md#L43>)
and [ADR 0010](<https://github.com/blater/river/blob/75039adc0461dd4b8f37f8b5b200032e2bac9936/docs/adr/0010-status-diagnostics-ownership-and-fatal.md#L20>)
now state the storage-trust and admission distinction. They preserve structural,
identity and recovery contracts and permit undetected post-admission content
damage. No standalone integrity utility is required for this delivery.

## Actions now, in order

1. Record this review with the delivery evidence and explicitly carry R1 as open
   follow-up work. Keep the original two P2 findings closed. The current closed
   ticket should not be read as acceptance of the later public-codec recommendation.
2. Deliver the narrow R1 encapsulation cleanup, or document an actual required
   external encoded-row contract. Update its callers and boundary/ownership tests
   together; preserve actual external input rejection and required structural checks.
3. Schedule R2 as the next focused projection/DRY improvement. Include R3's small
   ownership cleanups only where they naturally belong; avoid a broad generic
   codec or mutation-framework rewrite.
4. For changed code, run the focused row/value/SQL tests, affected-module checks
   and normal feature checkpoint. Measure the affected workload at the resulting
   source revision. Do not rerun a broad matrix solely to add review evidence.
5. Continue the [indexed-read work-amplification investigation](../../plans/river-indexed-read-work-amplification.md)
   for the larger Stock Level gap. Validation removal alone has not been shown to
   close it. Keep explicit deep content/integrity inspection outside critical paths
   and leave its standalone utilities deferred.
