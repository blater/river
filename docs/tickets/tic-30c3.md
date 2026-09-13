---
id: tic-30c3
status: open
type: epic
assignee: blater
delivery: none
tags:
    - performance
    - tpcc
created: 2026-09-04T14:59:38.224581Z
---
# Reach competitive TPC-C-derived transaction performance

The current strategy has three independently owned implementation epics:
`tic-carcharoth` (reusable execution metadata), `tic-rowlie` (commit-force overlap), and `tic-primula`
(transport waits/writes). Priority is execution, then overlap, then transport;
this is scheduling preference, not a false dependency between whole epics.
The immediate shared evidence ticket is tic-da4e.

The 2026-09-11 M5 diagnostic median was approximately447 River JVM versus1126
MariaDB TPS, with differing transports and short samples. It is not an engine-only
comparison, a native result, or a forecast of additive gains. See the ledger.

Preserve the independent P0 correctness owner tic-5db4/tic-1dda and the formal
500-TPS/parity gates tic-c7bb/tic-9c58. Their explicit prerequisites remain
mandatory for their claims. Lifecycle certification and independent comparison
sidecar work remain separately owned. Retired phase epics e5ff/723f are historical
supersessions, not passed performance gates.

Completion requires reviewed dispositions of all selected mechanisms and formal
claims supported by eligible external artifacts under the existing gate owners.
[The current handover](../plans/performance-three-epics-handover.md) supersedes old
blanket P0→P1→Payment→profile ordering; it does not waive correctness prerequisites.
