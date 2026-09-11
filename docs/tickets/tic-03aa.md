---
id: tic-03aa
status: open
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlParser

File: `river-sql/src/main/java/io/riverdb/sql/SqlParser.java`. Baseline slopwatch score: **94.379**.

## Approach

Review `SqlParser.parseDataStatement`, `SqlParser.parseQuery`, `SqlParser.parseTemplate` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

Final approach: retain the existing transaction, catalog, and data dispatch
owners, and remove only the private forwarding helpers with no callers in
`SqlParser` (`setIdentifier`, literal/number probes, and character wrappers).
The active keyword and grammar paths remain unchanged.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-sql` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
