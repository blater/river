# River

River is a new relational database implemented in Java. Its target is a
high-performance, crash-safe single-node database with SQL and JDBC access,
followed by a replicated journal and operational failover.

Development is ordered around proved vertical slices: durable formats and a
recoverable indexed table come before broad SQL or networking. The complete
architecture and execution baseline lives in [docs/plans](docs/plans).

## Current milestone

River is implementing **M0 (architecture ready)** and **M1 (recoverable indexed
table)**. Interfaces, durable formats, and public behavior are not stable until
their named gate has passed.

See the [project implementation plan](docs/plans/river-project-implementation-plan.md)
for deliverables, dependencies, gates, and milestone definitions.
