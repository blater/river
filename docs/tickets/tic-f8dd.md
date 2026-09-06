---
id: tic-f8dd
status: open
type: bug
priority: 1
assignee: blater
delivery: code
tags:
    - correctness
    - storage
    - tpcc
created: 2026-09-06T17:38:02.569133Z
---
# Investigate unchanged-master TPC-C checkpoint CORRUPTION

Unchanged master 4ee882b produced checkpoint_failed/CORRUPTION after a 30-second standard tiny TPC-C run; measured retries/errors were zero. Failure occurred querying SELECT COUNT(*) FROM order_line in TpccInvariants.verifyBusiness, and database shutdown returned CORRUPTION. This blocks performance acceptance.

## Design

Retain failed evidence /private/tmp/river-commit-force-opportunity-20260906/control-long-3. Runtime was OpenJDK 26.0.2.1, seed42, serializable,10terminals,1warehouse,warmup5s,measured30s; custom timing-event-only JFR enabled but production source had no probes. Reproduce and identify the first storage invariant failure before selecting a fix. Do not weaken checkpoint validation or attribute failure to JDK/background load without evidence. Related investigation tic-f539.

## Acceptance Criteria

Identify root cause and add a focused deterministic regression test; prove valid scan/checkpoint and cleanup through the real path; independent storage/recovery review; passing affected-module tests and repeated unchanged-workload samples with complete invariants.

