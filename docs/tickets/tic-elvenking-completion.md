---
id: tic-elvenking-completion
status: open
type: feature
priority: 1
assignee: blater
delivery: code
base-commit: 75039adc0461dd4b8f37f8b5b200032e2bac9936
branch: feature/elvenking-completion
evidence:
  - docs/delivery/evidence/2026-09-28-tic-elvenking-completion-review.md
tags:
  - performance
  - storage
  - sql
created: 2026-09-28T15:02:03Z
---
# Complete trusted-row boundaries and physical text liveness

Implement the outstanding recommendations in the
[completion review](../delivery/evidence/2026-09-28-tic-elvenking-completion-review.md)
of [tic-elvenking](tic-elvenking.md). Keep its original two P2 findings closed;
this ticket owns the remaining encapsulation and projection work. Preserve
the established storage-trust policy and real external input admission.

## Required work

1. **Internalize stored-row decoding.** Remove the unused public checked
   `StoredTableRowCodec.decode` overloads, `StoredTableRowExternalAdmission`,
   and `RelationalStoredRowAccess`. Place the decoder and its callers at an
   owning internal boundary without a row-to-relational dependency, a public
   unchecked setter, a validation flag, or a second row representation.
   Migrate all River-owned callers/tests together. Keep identity, layout,
   range, text-access and destination-capacity checks needed for safe reads.
   SQL, protocol and supported embedded raw input must still reject malformed
   UTF-8 and invalid typed values at admission.
2. **Share physical-column demand with projection liveness.** Replace the
   separate `SqlBoundBlockPlans.physicalTextUsed` name scan. Extend the
   existing dependency/liveness owner to prepare the physical columns that
   the bound plan actually needs, using resolved column identity and
   transitive dependencies. Supply that prepared set to the physical block
   decoder outside the per-row loop. Preserve columns used by filters, join
   conditions, ordering, grouping, aggregates, HAVING and SELECT-all; skip
   unused inner projections even when they name text.
3. **Consolidate local ownership.** Review the relational-to-SQL dependency
   created by `SqlAcceptedRow` while preserving its session/table-bound
   admission guarantee and the single mutation path. Remove the duplicate
   source-range check in `SqlResultTextLanes.setUtf8` where the arena's owning
   copy already checks it. Audit the final `table.isValidRow` in
   `SqlInsertRowEncoder`: identify which invariants are established there and
   remove only checks proved redundant. Record a reason for any retained
   check. Do not introduce a generic codec, adapter or mutation framework.
4. **Complete the evidence.** Commit the completion review with this ticket
   and record each finding's final disposition. Run focused row/value/SQL
   tests, affected-module checks, source policy checks and a clean feature
   checkpoint. Keep a warmed allocation check through the production
   stored-row entry. Exercise real nested numeric/text SQL paths, raw malformed
   input rejection, pending writes, rollback/restart, ownership and structural
   failure boundaries. Measure a representative affected workload at the
   final source revision with an adjacent control if its result is surprising;
   report host variation and do not claim a gain from short local runs.

## Acceptance

- No unused public encoded-row admission path, duplicate full validator or
  access-token dependency remains. Actual external entry points still reject
  malformed values; trusted stored publication remains internal and bounded.
- One plan-owned physical demand decision skips dead inner text projections
  while retaining every semantically required text column. Tests cover aliases,
  predicates, ordering, grouping/aggregates, HAVING and SELECT-all as well as
  a real SQL scan and invalid structural metadata.
- Accepted SQL rows retain session/table ownership and one mutation path. The
  INSERT admission audit names the surviving first-admission checks. Result
  text remains owned after the source buffer is reused.
- The final exact commit has passing focused and affected-module tests, clean
  full check, independent correctness/ownership review, diagnostic workload
  evidence and a recorded decision. Keep the wider indexed-read investigation
  in [tic-72e5](tic-72e5.md); it is not a prerequisite or a claimed outcome.

## R3 ownership audit

`SqlAcceptedRow` is constructed and accepted only inside the SQL package. It
binds a specific relational session and table before the shared relational
mutation path consumes it. Moving the type without a secure construction owner
would broaden admission, so retain this production dependency. The two
`SqlResultTextLanes.setUtf8` overloads can delegate source-range checks to
`Utf8TextArena.appendTrusted`; they publish a lane only after a successful copy.

Retain the final `table.isValidRow(row)` in `SqlInsertRowEncoder`. The encoder
does not independently establish all non-null column, fixed numeric domain,
text validity/limit and complete row-layout guarantees before that call. In
particular, an omitted non-null column and same-descriptor fixed numeric value
reach it without those earlier checks. Removing it would require a separate,
coherent admission change across every field path.
