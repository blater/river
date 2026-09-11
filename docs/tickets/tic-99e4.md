---
id: tic-99e4
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlBooleanScalarBinder

File: `river-engine/src/main/java/io/riverdb/engine/sql/SqlBooleanScalarBinder.java`. Baseline slopwatch score: **93.915**.

## Approach

Review `SqlBooleanScalarBinder.bindNode`, `SqlBooleanScalarBinder.bind`, `SqlBooleanScalarBinder.column` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `14e4382d`, Luna/high implemented; Sol/high/lead approved. Three private
push overloads are removed; callers invoke the canonical operation directly with
identical sign-extended high words, literal high words and resolved scopes. No
state, allocation, stack/status or SQL semantic changes. Score 88.556 (93.915 before).

Nine focused Boolean/grouped/nested/joined predicate tests passed, with engine
checks and benchmark installation. Log: `/private/tmp/river-tic-99e4-build.log`.
The epic's light JVM sample/all passed at 307.71 TPS, p99 62.194ms, zero failed/
unknown outcomes, valid invariants and graceful cleanup, consistent with recent
short controls. Artifact:
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_044200_f3d6b376`.
