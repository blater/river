# River

River is a new relational database implemented in Java. Its target is a
high-performance, crash-safe single-node database with SQL and JDBC access,
followed by a replicated journal and operational failover.

Development is ordered around proved vertical slices: durable formats and a
recoverable indexed table come before broad SQL or networking. The complete
architecture and execution baseline lives in [docs/plans](docs/plans).

Useful database function is the priority. Infrastructure, observability, and
review work must serve an immediate production-kernel consumer and stop when it
is unblocked. Planned modules remain outside the active build and dependency
graph until they receive production code.

## Current milestone

**P00 is passed and the foundation baseline is sufficient to proceed.** River's
active delivery priority is **M1 (recoverable indexed table)**, beginning with
S1, an inspectable empty database. Residual M0/G0 checks are performed only
when an immediate kernel consumer needs them; they do not justify a continuing
foundation-only workstream or block unrelated functional implementation.

Interfaces, durable formats, and public behavior are not stable until their
named gate has passed.

See the [project implementation plan](docs/plans/river-project-implementation-plan.md)
for deliverables, dependencies, gates, and milestone definitions.

## Build and validation

JDK 25 is required. Run the complete initial-phase validation locally:

```sh
./verify
```

The command uses the checksum-pinned Gradle wrapper and an isolated
repository-local Gradle home, then runs a clean compile, static source policy,
module dependency checks, and all tests. GitHub verification is intentionally
manual during the initial build phase; local validation is the merge gate.

To prove that committed `HEAD` builds without ignored or untracked worktree
inputs, reuse an already populated absolute Gradle user home and run the
offline detached-checkout gate:

```sh
RIVER_GRADLE_HOME=/absolute/path/to/gradle-home ./verify-clean-checkout
```
