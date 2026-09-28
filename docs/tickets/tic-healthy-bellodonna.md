---
id: tic-healthy-bellodonna
status: open
type: feature
priority: 1
assignee: blater
parent: tic-isildur
delivery: code
tags:
    - performance
    - sql
    - storage
created: 2026-09-28T15:54:47.596326Z
---
# Carry one projected-column contract into descriptor storage reads

This first slice of [tic-isildur](tic-isildur.md) removes per-row work for
unreferenced values without changing the index or durable row layout. The
existing block liveness pass already determines physical demand for simple
block scans; the JOIN text usage check is separate and coarser. The descriptor
reader still retains and copies the full base row and publishes every numeric
column.

## Design

- Prepare a reusable required-column set at SQL bind or scan preparation from
  projections, predicates, JOIN conditions, index-version rechecks, ordering,
  grouping, aggregates and other semantic consumers. Use bound column identity
  and transitive liveness; when demand cannot be proved, require the full row.
  Replace the separate JOIN text usage decision rather than maintaining two
  competing liveness calculations.
- Pass that set through descriptor scans to the canonical row-access owner.
  Decode fixed values directly from stored offsets; inspect and copy text only
  for selected columns. Avoid intermediate full-row copies when ownership and
  pin lifetime make direct access safe. Keep the existing full-row path for
  mutation and callers requiring all values until they use this same contract.
- Preserve tuple-index bound rechecks even when their columns are absent from
  the SQL output. Do not specialize for Stock Level or change benchmark SQL,
  schema or indexes.

## Acceptance Criteria

- Focused SQL and storage tests cover unused numeric and text values,
  predicates, JOIN roles, aliases, ordering, grouping, nulls and index-bound
  rechecks, plus pending writes, snapshots, isolation and cancellation.
- Mechanism evidence shows fewer bytes copied and fewer values decoded on the
  unchanged full Stock Level path. Structural base-tree searches are expected
  to remain until the next slice and must be reported separately.
- Run two identical short control and candidate samples and longer interleaved
  samples if variation or a directional shift needs explanation. Include New
  Order as an update control. Record artifacts, tests and decision in the
  performance checkpoint; do not claim a gain from a noisy TPS pair alone.

## Current investigation

The selected-column reader and JOIN demand path pass a clean full test build,
including a pending wide-row indexed scan. An exploratory pinned callback was
removed after independent review found writable buffer exposure and unsafe
reentry during a page pin. The safe reader retains the full row once and
decodes it directly from its retained read-only buffer. Short full Stock Level
and New Order samples show no repeated regression but no stable gain. The
593.840 TPS exploratory run was contaminated by other high CPU processes and
is excluded. See the [checkpoint](../performance-checkpoints.md#2026-09-28--projected-descriptor-read-investigation).
The feature remains open pending measured mechanism evidence and the canonical
head lookup replacement in the parent epic.
