---
id: tic-bfca
status: closed
type: investigation
assignee: blater
parent: tic-761e
delivery: evidence
evidence:
    - /Users/blater/src/ingres/river-harness/docs/tickets/standalone-river.md
    - /private/tmp/river-harness-lifecycle-0909/runs/river_harness_20260909_161623_6478d126
tags:
    - riverd
    - benchmark
    - harness
    - integration
deps:
    - tic-4cb6
created: 2026-09-04T15:23:11.996038Z
---
# Run external river-harness against the standalone River server

Update river-harness in its own repository to launch the installed `river`
executable with `river server start`, then execute its workload through the
public River wire protocol. River remains a separate server process.

## Design

Accept an explicit executable path independently of any River source checkout.
Use the consumer contract in tic-4cb6: private per-run data directory,
loopback, allocated port, readiness records and generated client configuration.
Use the existing Go database/sql protocol adapter, adding TLS and token
authentication. Do not embed the engine or launch a CLI process per statement.
Keep connections and transaction boundaries faithful to the shared workload.

Delete `--river-home`, River Gradle invocation, server classpath parsing, Java
server-main knowledge, process-class inspection and private security coupling.
Use the supported stop command for the owned instance and clean up on success,
failure and interruption. Never stop a pre-existing user server.

Keep MariaDB and River behind the harness's existing target adapters. Shared
TPC-C inputs, transaction semantics and invariants remain database-independent;
SQL bindings, connections and lifecycle belong to each target. Adding another
database later must not require changes to River. PostgreSQL wire support and a
PostgreSQL target are separate follow-ups, not prerequisites for this ticket.

## Acceptance Criteria

Link the harness ticket and delivery commit. From an environment without a
River source checkout, run a focused River sample and an all-five-family sample
through the standalone server with passing invariants and no failed or unknown
outcomes. Verify owned-process cleanup after success, failure and interruption.
Run a matching MariaDB smoke to check the existing target still works. Record
commands, build/version labels, workload configuration and results. Keep this a
functional migration; statistical comparisons and a platform matrix are outside
scope.

## Delivery notes

Work is on River branch `ticket/tic-bfca-standalone-harness`. Before the harness
changes were exercised, the existing native executable passed two identical
short JDBC workload controls: 160.6 and 160.2 committed TPS. Configuration:
standard tiny mix, serializable, 10 terminals, one warehouse, seed 42, 2s
warmup and 10s measurement; native O3/PGO with production resource defaults.
Both completed with zero failed/exhausted transactions and owned-data cleanup.
Artifacts: `/private/tmp/bfca-native-baseline-{1,2}/`. These controls use River's
acceptance workload and must not be compared with the Go harness's TPS.

## Accepted delivery

Harness branch `ticket/standalone-river`, commit `15ca297`, implements the
standalone lifecycle, authenticated Go client, current value-length encoding
and pooled-connection validity. Its delivery ticket is
`river-harness/docs/tickets/standalone-river.md`. The public consumer contract
is documented in `docs/riverd-cli.md`. No River production code changed.

Focused New-Order and all-family River runs passed. The matching MariaDB
all-family run passed with the same comparison key: sample profile, four
workers, one warehouse, seed 42, 2s warmup, 10s measurement, maximum 20 retries.
Artifacts under `/private/tmp/river-harness-lifecycle-0909/runs/` are
`river_harness_20260909_161623_6478d126` (River) and
`river_harness_20260909_161711_874e7ed8` (MariaDB). Both have zero failed or unknown
outcomes, valid invariants and graceful cleanup.

Three retries exhausted under this small profile on both engines: River 5 and
MariaDB 28 measured failures. Those runs remain failed evidence. This is
contention with an insufficient retry budget, distinct from the fixed codec
and warmup pool defects. Defaults and transaction semantics are unchanged.

Full Go tests, River adapter race tests, go vet, shell syntax and the installed
checkout smoke passed. Slopmark changes were reviewed; authentication and
readiness parsing remain at the client/process boundaries. Native JDBC controls
were 160.6/160.2 TPS before and 160.9/163.7 after, with all checks passing and no
observed regression. These are separate from Go harness TPS.

The formal family-level comparison and parity campaigns remain open in
`tic-7ec5` and `tic-9c58`; this delivery does not claim to satisfy them.
