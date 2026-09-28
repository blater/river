# Headerless table rows and removal of historical-layout resolution

Ticket: [tic-celeborn](../../tickets/tic-celeborn.md). Implementation worktree:
`/private/tmp/river-compact-row-header`, branch
`ticket/tic-celeborn-compact-row-header`, based on accepted checkpoint
`e3225ffd` (`perf-checkpoint-20260928-indexed-read-head-directory`).

## Result

Stored descriptor-table row payloads have no separate header. All 32 former
header bytes are removed, together with their encoding, decoding, repeated
identity/layout checks and the unused historical-layout resolution path.

| Removed field | Bytes | Why it is unnecessary in the row |
| --- | ---: | --- |
| Magic | 8 | The table access path already selects the row representation. |
| Constant version | 4 | Database format admission belongs to the existing control-file open boundary. |
| Reserved flags | 4 | There is no implemented consumer. |
| Row layout ID | 8 | The admitted table descriptor owns layout; current successors preserve columns and layout. |
| Logical row ID copy | 8 | Fetches and scans already supply authoritative logical identity. |

The payload now contains the null bitmap, fixed column slots, and variable
column bytes. Field offsets, encoded size, maximum admitted row size and
projected retention all use this representation directly. There is no zero-size
header codec, replacement marker, reserved padding or alternate old reader.

A row with one non-null BIGINT occupies nine payload bytes: one null-bitmap byte
and eight value bytes. The previous representation occupied 41. This is an
exact format saving, not a measured TPS improvement. Heap slot metadata,
MVCC/version metadata, page headers and WAL framing are separate structures.

## Logical identity and ROWID

Logical identity remains part of the actual database behavior. It is used for
point lookup, secondary-index references, updates, deletion and visible-version
selection. The deleted payload copy only checked an identity already known to
the caller; it did not allocate or discover that identity.

[RelationalDescriptorScanAccess](../../../river-engine/src/main/java/io/riverdb/engine/relational/RelationalDescriptorScanAccess.java)
obtains logical identity from tuple results or the base scan key and publishes it
in `RelationalRowIdentityResult`. Point access passes the requested logical ID
to the storage lookup. These sources can support a future SQL `ROWID` expression
without adding bytes to the row payload. SQL `ROWID` is not implemented by this
change.

`RelationalDescriptorRowPathTest.wideRowNullsIdentityAndPrimaryMutationSurviveAbortAndReopen`
exercises the relevant real path: aborted allocation, committed identity,
primary-key update, checkpoint, reopen, lookup by logical ID, scan identity and
delete. Identity survives independently of the row's column bytes.

## Architectural removal

The current successor contract explicitly requires unchanged row layout and
columns. The historical resolver did not establish a currently supported
mixed-layout table feature. It has been removed as requested:

- Delete `StoredTableRowHeaderCodec`, `StoredTableRowHeader`, their result state
  and tests for their removed contract.
- Delete `RelationalDescriptorHistoricalValidation` and
  `CatalogHistoricalTableOpener`, including their service/lifecycle entry points.
- Remove historical manifest search and the cache APIs that admitted or selected
  an independently requested historical layout. Preserve exact descriptor lookup
  and pins used by real metadata transactions.
- Reuse `RelationalDescriptorRowBuffer` for storage validation and delete
  `RelationalDescriptorRowBytesValidation`. This also removes a separate buffer
  owner and repeated header decode; retained rows use the existing direct
  read-only decoding path.
- Make the now-stateless row decoder static and remove unused logical-ID
  arguments throughout encode/decode callers. IDs remain where lookup and
  mutation actually consume them.

Existing snapshot visibility, version history, committed row identity, schema
pins, SQL constraints and WAL ordering remain owned by their existing services.
Mixed physical row layouts are unsupported. A future layout-changing DDL feature
must define its storage behavior when implemented; this change carries no
speculative compatibility machinery for it.

## Durable format boundary

[ADR 0004](../../adr/0004-durable-identities-pages-and-rows.md) records the new
layout. The existing database control codec advances from major version 1 to 2.
Its existing open-time version check rejects earlier databases; no new per-row
or per-page admission pass is introduced. There is no migration or backward
reader. Old database directories must be recreated for this build.

The control-codec test exercises old and unknown major versions with correct
checksums, so rejection is specifically due to format compatibility, rather than
an accidentally invalid checksum.

## Verification

The final affected-module run passed on 2026-09-28 (Europe/London), using
GraalVM 25 and isolated Gradle home/project caches:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/river-celeborn-gradle \
./gradlew --no-daemon --offline \
  --project-cache-dir /private/tmp/river-celeborn-project-cache \
  :river-format:test :river-storage:test :river-engine:test verifySourcePolicy
```

| Suite | Tests | Failures/errors/skips |
| --- | ---: | ---: |
| river-format | 78 | 0 |
| river-storage | 48 | 0 |
| river-engine | 1,090 | 0 |

Source policy passed. `git diff --check` passed. Final build log:
`/private/tmp/river-celeborn-modules.log` (`BUILD SUCCESSFUL in 2m 57s`).
The format and storage tasks were up to date on the final invocation after
passing with the same final source in the preceding affected-module run.

The byte fixture proves that a mixed nullable/text row starts directly at its
null bitmap. Exact single-page admission fixtures now consume the reclaimed
32 bytes, and projected-retention tests use the new bitmap origin. Engine
coverage includes catalog/index changes, transactions, recovery, checkpoint,
reopen, scans, null/text decoding, snapshot and mutation behavior.

Ticket validation has the same 167 existing repository issues as the baseline;
the new ticket introduces none. This does not turn pre-existing delivery
metadata failures into a passed global ticket-validation result.

Allocation investigation: with the original 10,000-read warmup, consecutive
100,000-read windows allocated 11,464, 616, 0 and 0 bytes on the local GraalVM 25
JVM. The test now warms 300,000 reads and retains its 256-byte ceiling, checking
both 100,000 and 1,000,000 reads. Temporary measurement output was removed.
Diagnostic XML: `/private/tmp/river-celeborn-allocation-windows.xml`.

Slopmark is an architectural review signal, not a correctness or performance
verdict. A reconstruction of the touched production sources at `e3225ffd` and
the final touched sources produced these scores:

| Owning class | Before | After |
| --- | ---: | ---: |
| CatalogDefinitionStore | 131.794 | 50.9696 |
| SchemaCache | 71.5677 | 60.1102 |
| RelationalDescriptorRowValidation | 11.0896 | 7.82365 |
| StoredTableRowDecoder | 22.5926 | 19.735 |

Raw reports: `/private/tmp/river-celeborn-slopmark-before.txt` and
`/private/tmp/river-celeborn-slopmark-after.txt`. The tool reports incomplete
boundary-analysis coverage for some classes; the scores are not approval.

## Remaining work and recommendations

1. Keep layout interpretation in the table descriptor and logical identity in
   the access path. Do not restore duplicate fields or historical dispatch to
   satisfy removed tests or hypothetical future features.
2. Continue tic-celeborn with the ownership change needed to avoid intermediate
   row retention and the remaining value-container transfers. The normal heap
   fetch still retains bytes before releasing its page pin. This change does
   not claim a generally zero-copy indexed-read path.
3. Measure the remaining copies and decoding on full Stock Level before choosing
   the next mechanism. The 32-byte reduction alone does not establish that the
   MariaDB performance gap is resolved.
4. Before promoting a performance checkpoint, obtain the independent durable-
   format review required by AGENTS.md, run the clean integration checkpoint and
   capture matched control/candidate samples under the repository's existing
   performance process. This document records author review and working-tree
   validation; it is not independent approval or a benchmark acceptance claim.

The wider ticket remains in progress. Header removal and its affected-module
checks are complete in this worktree. Borrowed access and the remaining copy work
have not been implemented by this slice. Integration and performance-checkpoint
promotion remain pending.
