---
id: tic-primula
status: closed
type: epic
priority: 1
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - protocol
created: 2026-09-13T11:46:05.507685Z
---
# Reduce protocol waits and transport writes

### Outcome

Reduce avoidable preparation exchanges, waits and small writes through River's
existing authenticated client/server path while preserving SQL transactions,
response ordering, cancellation and uncertain-outcome handling.

### Children and order

1. `tic-edoras`: audit actual current exchanges and select useful mechanisms.
2. `tic-gwindor`: client-lifetime prepared-handle reuse, if reuse is missing and material.
3. `tic-morgoth`: one bounded ordered pipelining/coalescing implementation, if admitted.
4. `tic-rian`: interoperable integrated workload evidence.

The design consumes `tic-da4e`; it does not wait for the entire WAL epic.
The two code candidates share client/transport files and are scheduled serially
by the lead, but have no invented technical dependency on each other.
`tic-6f28` already removed prepared-close responses; `tic-7a32` already shares
server plans. The existing program protocol is an option to evaluate, not a
mandate to collapse Payment to one request or build another executor.

### Completion

Admitted mechanisms work through the supported server and both Java and Go
consumers, with bounded pressure/cancellation/streaming and exact transaction
outcomes. Paired runs preserve SQL/workload/transport semantics; request count
alone is not success. Rejected mechanisms have explicit evidence dispositions,
not empty implementation claims. The final gate closes only after all candidate
outcomes and cross-repository consumer deliveries are accounted for.

[Lead handover](../plans/performance-three-epics-handover.md) specifies shared
acceptance and independently owned harness/comparison boundaries.

### Completed bounded outcome, 2026-09-14

Edoras establishes the admitted common-binding ownership contract. Gwindor's
prepared catalogue and exact cleanup are locally delivered in river-harness
at4ff2a71d, with no remote publication at the user's instruction. Morgoth is
rejected without code. Nimloth's separately reviewed rollback safety fix is
integrated3d70a826. Rian accepts repeated local workload benefit, compatibility,
mechanism and cleanup evidence after independent review. No driver cache,
pipelining, second outcome policy or transport rewrite is introduced. This
completes the protocol epic; WAL/P0 and formal comparison gates remain separate.
