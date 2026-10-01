---
id: tic-damrod
status: in_progress
type: story
priority: 1
assignee: blater
delivery: code
base-commit: 4ac490321a9f1110900d34562d671a6709af8fc5
branch: ticket/tic-damrod-dry-refactoring
tags:
    - refactor
    - dry
    - maintainability
created: 2026-10-01T14:47:06.64208Z
---
# Consolidate cataloged DRY responsibilities into concise, coherent owners

## Description

Consolidate the duplicated responsibilities in the 2026-10-01
[WET catalog](../wet.md) into concise, coherent code. Shared policy should have
one clear owner, callers should express their specific work directly, and the
superseded implementation should disappear in the same slice.

This is structural refactoring with no assumed throughput gain. The observable
outcome is fewer independent implementations of the same responsibility and
simpler affected call paths, with existing behavior verified through real
consumers. Follow [AGENTS.md](../../AGENTS.md) and
[the manifesto](../../manifesto.md) rather than introducing another policy.

### Scope and sequence

The scope is fixed to WET-01 through WET-18. Refresh each finding against the
implementation base before editing; catalog suggestions are candidates, not
proof that superficially similar contracts are interchangeable. Implement P1
first, then P2, then P3. Each slice changes one listed responsibility and its
necessary River-owned callers and tests together. WET-07 and WET-08 may share
one slice if the same ordering/name-resolution owner requires it.

| Priority | Catalog entries | Intended consolidation |
| --- | --- | --- |
| P1 | WET-01 | Use `FormatBytes` for repeated fixed-width durable primitives. |
| P1 | WET-02 | Use one SQL comparison operator-to-truth mapping. |
| P1 | WET-03 | Give exact-numeric precision and scale rules one type-policy owner. |
| P1 | WET-04 | Share relational tuple-key equality and physical-key lookup. |
| P1 | WET-05 | Share descriptor DDL completion, pin release, and result publication. |
| P1 | WET-06 | Share canonical numeric text parsing; keep record-specific admission at its boundary. |
| P1 | WET-07, WET-08 | Make name-comparison contracts explicit and consolidate overlapping ordering policy; remove a path only after verifying that it is superseded. |
| P2 | WET-09 | Complete the existing JDBC metadata result-state owner where contracts match. |
| P2 | WET-10 | Consolidate stable lock slot mechanics without obscuring typed storage. |
| P2 | WET-11 | Share response capacity calculation where client/server admission contracts match. |
| P2 | WET-12 | Simplify common result-value access while keeping row/command lifetimes explicit. |
| P2 | WET-13 | Share catalog status-detail stamping, preserving failure and cleanup ordering. |
| P2 | WET-14, WET-15, WET-16 | Reuse cohesive storage fixtures, SQL assertions, and embedded query lifecycle assertions. |
| P3 | WET-17 | Share genuinely common paged-array allocation policy while retaining primitive storage. |
| P3 | WET-18 | Share benchmark counter saturation arithmetic. |

Source locations and duplication evidence remain in the catalog; do not copy
another source inventory into this ticket. Update the affected catalog entry
with its disposition and implementation reference as each slice lands.

## Design

- Prefer an existing owner and direct delegation. Add a small local helper only
  when it gives a real shared responsibility a clearer home. Remove redundant
  forwarding, state, branches, imports, and obsolete tests with the replacement.
- Judge concision across the owner and all callers. Moving repeated bodies into
  many one-line wrappers, introducing a configurable framework, or spreading
  one operation across more objects does not satisfy this ticket.
- Preserve deliberate differences in case handling, null semantics, numeric
  domains, admission, lifecycle, and failure precedence. Share the common rule;
  keep consumer-specific work local. Trace live consumers before removing a
  class described as legacy.
- Keep primitive storage, buffer ownership, allocation/copy behavior, and
  release ordering explicit. Do not add per-row allocation, captured callbacks,
  boxing, or generic containers to simplify a hot path.
- The maximum change shape per slice is one cataloged responsibility and its
  necessary callers/tests, except the coupled WET-07/WET-08 case above. This
  ticket owns no new executor, queue, retry loop, value representation, format,
  protocol, SQL feature, diagnostics framework, or dependency-graph redesign.
- Stop a proposed extraction if it adds indirection without simplifying the
  complete responsibility or conflates distinct contracts. Record the evidence
  for retaining a local implementation. A discovered defect or required
  architectural redesign becomes a separate prerequisite; discovery does not
  expand this ticket.

## Acceptance Criteria

- [ ] Every WET-01 through WET-18 entry has a reviewed disposition: consolidated
  with an implementation reference, already resolved on the implementation
  base, or retained with concrete evidence of distinct contracts or increased
  complexity from extraction. Deferred work has a linked ticket and prevents
  claiming the catalog fully resolved.
- [ ] Each implemented responsibility has one clear policy owner. All affected
  River-owned callers use it; duplicate implementations, transitional adapters,
  dead state, and superseded tests are removed in the same slice.
- [ ] Review confirms that the affected code is more concise and easier to
  follow as a whole. Avoid arbitrary line-count, source-token, or score targets;
  explain the removed duplication and any necessary new abstraction briefly.
- [ ] Focused tests exercise the shared owner through materially different
  callers, including relevant failure and cleanup boundaries. Affected-module
  checks pass with `--no-daemon`; required public behavior, durable bytes,
  SQL semantics, status precedence, and ownership remain covered.
- [ ] Durable, recovery, concurrency, and security changes receive independent
  review appropriate to their boundary. Hot-path changes retain allocation/copy
  expectations and use the proportionate measurement and slopmark checks in
  AGENTS.md. Expand validation only for a concrete concern; no new benchmark
  campaign or performance claim is part of this ticket.
- [ ] The catalog records final dispositions and the ticket records immutable
  delivery commits before closure under the existing ticket delivery policy.

