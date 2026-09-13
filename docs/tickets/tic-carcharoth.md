---
id: tic-carcharoth
status: open
type: epic
priority: 1
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - execution
created: 2026-09-13T11:46:05.487856Z
---
# Reuse schema-bound SQL execution metadata

### Outcome

Reduce repeated execution setup under unchanged SQL, schema, authorization and
transaction semantics. Extend existing preparation/binding owners; do not add a
second plan cache or retain transaction pins in session-lifetime metadata.

### Children and order

1. `tic-da4e`: fresh current-master profile; shared evidence input for all three epics.
2. `tic-eowyn`: reuse immutable derived binding metadata.
3. `tic-bracegirdle`: replace catalog-wide reverse-FK discovery.
4. `tic-telemnar`: move existing unchanged-key avoidance before discovery.
5. `tic-twofoot`: eliminate measured unused scratch reset work.
6. `tic-miriel`: evaluate the integrated admitted mechanisms.

Children 2–5 are separate conditional merge boundaries. Evidence determines
which are worth implementing; do not falsely sequence independent FK mechanisms.
Existing shared preparation `tic-7a32` and transaction bindings `tic-5c21` are
accepted starting points, not missing features.

### Completion

Every child has a reviewed delivered or explicitly rejected/superseded outcome;
the final gate proves end-to-end behavior, ownership safety and repeatable
benefit for accepted optimizations. No promised multiplier. Formal MariaDB
parity remains owned by `tic-9c58`, outside this implementation epic.

[Lead handover](../plans/performance-three-epics-handover.md) owns current sequence
and shared validation. Original historical plans remain architecture evidence.
