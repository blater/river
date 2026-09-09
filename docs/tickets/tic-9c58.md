---
id: tic-9c58
status: open
type: epic
priority: 1
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - tpcc
    - mariadb
    - parity
    - alpha3
deps:
    - tic-c7bb
created: 2026-09-04T14:59:38.679044Z
---
# Parity gate: compare external stress artifacts at Alpha3 scale

First produce semantically eligible River and MariaDB stress artifacts, compare
them in an external engine-neutral sidecar, quantify the gap by family and
mechanism, and satisfy the normative relative and capacity gates.

## Design

Use the standalone `river server` lifecycle and public River protocol
connection. Keep target lifecycle, connection and SQL bindings in harness
adapters so a PostgreSQL target or another database can be added later.
PostgreSQL wire support in River is a separate capability, not a dependency of
these runs. The initial delivery covers River and MariaDB; additional targets
are follow-ups.

River-harness remains the stress runner and emits a stable versioned result
artifact. Comparison tooling lives outside River and consumes artifacts through
a process/file contract; it neither imports River internals nor river-harness
implementation packages. Use identical manifests and paired/interleaved
samples. The normative gate remains at least 1,000 committed TPS at the 95%
lower bound, River median at least 80% of MariaDB, and qualifying-family p99 no
more than 20% worse.

## Acceptance Criteria

Eligible artifacts have identical semantic keys and passing invariants; the
external sidecar rejects incompatible schema or configuration; mechanism-
specific gaps are resolved in their owning repositories; the final Alpha3
matrix and provenance requirements pass.
