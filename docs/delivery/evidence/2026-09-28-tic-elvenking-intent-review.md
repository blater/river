# Review: trusted values implementation against tic-elvenking

Recorded: 2026-09-28 12:58 UTC.
Ticket: [tic-elvenking](../../tickets/tic-elvenking.md).
Policy: [Storage trust and content integrity](../../../AGENTS.md#storage-trust-and-content-integrity).

## Decision

The main changes honor the ticket's intent. Continue this implementation, but
resolve the two findings below before treating the storage-trust change as
complete. The descriptor-row path removes substantial repeated validation and
unchanged-value conversion; the policy is not yet applied consistently across
the remaining row paths.

This is an intent and source review, not final correctness or performance
acceptance. No production files were changed and no build or workload was
started by the reviewer.

## Reviewed source

The reviewed working tree is `/private/tmp/river-trusted-values`, including
`river-engine` and the associated `river-base` changes. Its base commit is
`f77bb51afd5bde178b1fd2ffed4843059a2bcf53`; the implementation was uncommitted
and actively changing during review. The base commit alone does not identify
the reviewed patch. Source links below refer to that worktree, with line numbers
as observed during review.

Both substantive findings were rechecked while writing this document and were
still present. ADR 0004 and ADR 0010 had acquired edits after the original
conversational review; their final alignment still needs review, but they should
no longer be described as untouched. Subsequent edits and test runs require a
fresh acceptance check against the exact final candidate.

## Finding 1 — P2: other trusted read/update paths retain full validation

[TableDefinitionRowCodec.isValidRow](</private/tmp/river-trusted-values/river-engine/src/main/java/io/riverdb/engine/relational/TableDefinitionRowCodec.java:20>)
still checks every field's domain, canonical null/padding representation and
UTF-8 content. Its text branch calls `Utf8Text.validate` at line 46.

This has production callers that handle already stored or internally produced
rows, including:

- [SqlMutationRowEncoder.copySourceRow](</private/tmp/river-trusted-values/river-engine/src/main/java/io/riverdb/engine/sql/SqlMutationRowEncoder.java:255>)
  revalidates the fetched source row before constructing an update.
- [SqlMutationRowEncoder.finishRow](</private/tmp/river-trusted-values/river-engine/src/main/java/io/riverdb/engine/sql/SqlMutationRowEncoder.java:248>)
  validates the complete assembled update again.
- [RelationalIndexLookup.copyRow](</private/tmp/river-trusted-values/river-engine/src/main/java/io/riverdb/engine/relational/RelationalIndexLookup.java:308>)
  validates a copied indexed row.
- [RelationalRowMutation.copyRow](</private/tmp/river-trusted-values/river-engine/src/main/java/io/riverdb/engine/relational/RelationalRowMutation.java:272>)
  performs the same complete validation after fetching a stored row.

These owners were unchanged apart from the separate descriptor implementation.
The codec's comment calls its representation transitional, but it still has
these callers; that label is not evidence that its cost or behavior is absent.

**Required resolution:** distinguish actual raw-input admission from trusted
stored-row access at these existing owners. Remove repeated semantic checks on
the latter and retain only the structural bounds required for the bytes used.
Keep validation of new assignments and SQL constraints where their validity is
first established. Do not remove the last validation of external raw input.

The ticket covers ordinary reads and updates, not only the descriptor path
exercised by Stock Level. If a path is obsolete, establish that and delete it
with its callers/tests; do not retain a second validation policy as a legacy
fallback. This finding does not require the separate indexed-storage redesign.

## Finding 2 — P2: numeric-only reads still scan unused text metadata

[StoredTableRowDecoder.decode](</private/tmp/river-trusted-values/river-engine/src/main/java/io/riverdb/engine/row/StoredTableRowDecoder.java:37>)
calls `StoredTableRowBounds.validate` before it considers `publishText=false` or
evaluates the stored-row filter.

[StoredTableRowBounds.validate](</private/tmp/river-trusted-values/river-engine/src/main/java/io/riverdb/engine/row/StoredTableRowBounds.java:12>)
loops over all columns, reads every non-null text slot and checks both contiguous
packing (`offset == textOffset`) and exact payload consumption
(`textOffset == length`). Consequently, a numeric-only read still inspects the
layout of strings it will never access.

Removing UTF-8 byte scanning is a material improvement in the mechanism. The
remaining work is a metadata walk, not another UTF-8 payload scan. Its measured
cost has not been isolated, so this review does not assign a throughput penalty.
However, canonical packing of unused text is not required to read bounded fixed
numeric slots and falls outside the ticket's intended access checks.

**Required resolution:** check row identity, the enclosing byte range and the
fixed prefix needed by numeric accesses without walking unused variable fields.
When text is actually accessed, bound that field's offset/length and the
destination capacity. Preserve failure behavior and ownership for required
accesses. Exact canonical packing and exhaustive consumption checks belong to
deferred explicit integrity inspection, not a prerequisite for a numeric read.

Apply this to the existing numeric-only path; do not introduce a general
projection planner or another executor to resolve the finding. Tests should
distinguish malformed metadata that affects an actual access from unused
content/layout that ordinary execution now trusts.

## Changes that follow the ticket

- `StoredTableRowBodyValidator` is deleted rather than retained behind a flag.
- Stored-row publication uses non-validating `setStoredScalar`,
  `setStoredTextBytes` and `setStoredNull` operations. Those setters are
  package-private in the engine's `SqlValueBuffer`.
- [SqlDescriptorMutationValues.buildUpdate](</private/tmp/river-trusted-values/river-engine/src/main/java/io/riverdb/engine/sql/SqlDescriptorMutationValues.java:60>)
  uses `copyTrusted` for unchanged fields. The old UTF-8 to UTF-16 to UTF-8
  conversion and its character scratch storage are removed.
- Public value-admission setters retain domain and text validation. New tests
  cover malformed external text and copying values across source-buffer reuse.
- Content-corruption tests are being updated to reflect the intentional trust
  policy, while structural/identity rejection remains covered.
- No standalone integrity utility, inspection scheduler, validation cache or
  compatibility mode was introduced in the inspected changes.

Moving `SqlValueBuffer` and its lane storage from `river-base` into the engine
is defensible: it lets the row codec use package-private raw stored-value setters
while other callers transfer already typed values. It replaces the former class
rather than introducing a second value representation. Much of the changed-file
count is the necessary import migration, which is not itself a scope violation.

## Evidence observed and remaining acceptance work

At the original review, existing JUnit reports from 12:55:27–12:55:28 UTC showed
42 passing tests, with no failures, errors or skips:

| Test class | Tests |
| --- | ---: |
| `RelationalDescriptorRowPathTest` | 12 |
| `RelationalDescriptorStorageValidationTest` | 2 |
| `SqlValueBufferTest` | 11 |
| `StoredTableRowCodecTest` | 12 |
| `SqlDescriptorTupleIndexScanTest` | 5 |

During write-up, a later focused run replaced the current report set with
`SqlTrustedStoredValueTest`: one passing test, timestamp 12:57:27 UTC. These
are observations of separate author-run reports, not a reviewer-run full suite
or proof that every current edit was tested together. `git diff --check` passed
during the source review.

The implementation still needs:

- Resolution and focused regression coverage for both findings.
- Review of the final admission/ownership contract and updated ADRs.
- Targeted evidence that the affected read/update paths no longer perform the
  removed validation or unchanged-text conversion.
- Candidate measurements against the captured controls for full Stock Level
  and affected updates, with the adjacent sample check required by the ticket.
- Affected-module checks, the clean feature checkpoint, and recorded final
  source/configuration/correctness/cleanup/performance outcomes.

Control workload logs were present; this review did not establish candidate
throughput or a performance improvement. The two findings concern completeness
of the agreed policy. Neither predicts that its resolution alone closes the
remaining MariaDB performance gap.
