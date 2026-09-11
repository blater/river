---
id: tic-03aa
status: in_progress
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

Final approach: retain catalog, data, and query ownership while moving the
transaction/session grammar into `SqlSessionCommandParser`. It shares the
parser cursor, keeps one reusable literal scratch for `SET TIME ZONE`, and
returns the existing no-match result to `SqlParser`'s ordered dispatch. The
active keyword order and grammar paths remain unchanged; unused forwarding
helpers in `SqlParser` are removed with the old owner.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-sql` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


Validation: source `39f30922`, Luna/high implemented; Sol/high and lead approved.
Original score 87.025 (94.379 before), session-command owner 0. SQL module checks,
`EmbeddedRiverApiTest`, `SqlAtomicStatementLifecycleTest` and installTps passed
with `--no-daemon`. The epic's light JVM sample/all passed at 293.04 TPS,
p99 62.849ms, 509 retries, zero failed/unknown outcomes, valid invariants and
graceful cleanup. No observed regression against the recent control range.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_033626_c68ac72e`.
