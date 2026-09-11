---
id: tic-055b
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlCommand

File: `river-sql/src/main/java/io/riverdb/sql/SqlCommand.java`. Baseline slopwatch score: **128.210**.

## Approach

Review `SqlCommand.lowerJoinAggregateSource`, `SqlCommand.aggregateOutputProjection`, `SqlCommand.copyBlockFrom` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-sql` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Active implementation

Owner: lead-integrated relational domain builder (`complete_9e2f`). Stable base:
`832ae0d4`. Branch: `ticket/tic-055b-m5-completion`. Worktree:
`/private/tmp/river-m5-055b`.

The current slice targets join aggregate source lowering and its existing
aggregate-output mapping owner. Parser-owned values retain the U00 binding
lifetime; no parked public carrier migration is admitted without review.

## Current validation (2026-09-11)

Join aggregate lowering delegates operandless-output materialization to the
existing aggregate set and group-expression construction to the existing
projection owner. The command retains mutation ordering and final source reset.

The existing column-constraint owner now owns its metadata arrays, identity
state, read/mutation admission, reset, and preparation/publication of the
existing column-growth reservation. `SqlCommand` remains the authoritative
column count and keeps its public facade. No parser state escapes into execution;
one owner object is created with the command, with no additional per-row
allocation or buffers. The superseded admission helper is deleted.

Focused parser, constraint, growth/reset, and real joined-aggregate pipeline
validation passed: 47 SQL tests and one engine test. The SQL runtime invocation
and module graph gates passed. Log:
`/private/tmp/river-055b-final-focused.log`.

The unchanged full-repository scorer covered 2,640 Java files, with 19 files at
or above 90. `SqlCommand` improved **128.210 → 102.944**;
`SqlCommandColumnConstraints` scores **77.087** and the other touched Java files
score zero. Artifact: `/private/tmp/river-055b-scores.json`.
**The ticket's below-90 acceptance requirement remains unmet.** Further scope
expansion is paused for the lead's acceptance decision; this is not a completed
or promoted ticket.
