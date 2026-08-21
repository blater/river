# ADR 0002: Provenance boundary

Status: Accepted

## Context

River is a new implementation whose initial repository license is AGPL-3.0.
Legacy Ingres source, tests, fixtures, messages, and formats remain separately
identified inputs even when approved as design and test references.

## Decision

Adopt the [provenance policy](../governance/provenance-policy.md) and
[artifact ledger](../governance/provenance-ledger.csv) as mandatory controls.
The project owner, acting as qualified provenance reviewer, approved the policy,
current dependencies, and the workspace Ingres source/test trees on 2026-08-09.

Legacy material may be used as a core functionality, kernel, and test reference.
Adapted logic, tests, fixtures, messages, and layouts retain an explicit source
link and adaptation classification. Every other non-original dependency,
dataset, fixture, or tool has a completed ledger entry before release use or a
published benchmark claim. River-owned seeded generators remain canonical
regression data.

## Invariants

- Missing identity, version, license, use, or approval blocks artifact use.
- Exposure to legacy implementation detail is disclosed on related work.
- A human or agent who reads outside code or documentation may specify the
  resulting River behavior but may not implement it; a different human or
  agent must implement from the River-owned handoff without access to the
  outside material or extracts from it.
- Outright copying is forbidden, including verbatim copying, mechanical
  translation, line-by-line or structure-preserving ports, renaming-only
  rewrites, and copying source tests, fixtures, layouts, code, or text.
- The provenance record names the reader/specifier and independent implementer
  and attests to the Chinese-wall separation before acceptance.
- External datasets are fetched on demand unless redistribution is approved.
- Compatible repository licenses do not prove attribution, notice, or
  provenance acceptability.

## Consequences

River can use the approved references without promising direct Ingres
compatibility. AGPL-3.0 remains the initial license; a later license change is a
new project decision rather than an implicit consequence of this approval.

## Alternatives

- Treating repository license compatibility as blanket permission was rejected.
- Requiring black-box-only use after the project owner approved reference access
  was rejected because it would discard useful kernel and test evidence.

## Required evidence

- Project-owner approval reference in the policy and ledger.
- Complete dependency/license inventory at each milestone.
- Provenance records for adapted legacy logic, tests, fixtures, messages, or
  layouts.

## Authoritative context

- [Provenance policy](../governance/provenance-policy.md)
- [Implementation plan P01](../plans/river-project-implementation-plan.md)
- [Performance data policy](../plans/river-performance-review-and-benchmark-plan.md)
