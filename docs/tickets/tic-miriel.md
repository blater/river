---
id: tic-miriel
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: evidence
tags:
    - performance
created: 2026-09-13T11:46:48.655709Z
deps:
  - tic-eowyn
  - tic-bracegirdle
  - tic-telemnar
  - tic-twofoot
---
# Validate reusable execution improvements on the integrated workload

Evaluate the exact integrated source after `tic-eowyn`, `tic-bracegirdle`,
`tic-telemnar` and `tic-twofoot` each has a reviewed accepted or
rejected disposition. This is evidence only; no code or harness repairs here.

Verify dependency dispositions explicitly; a superseded ticket's closed status is
not delivery proof. Record the subset implemented and any replacement IDs. Run
focused affected-family checks and sample/all controls/candidates under the
[shared measurement contract](../plans/performance-three-epics-handover.md).
Report throughput, p99, retries and each accepted mechanism's own setup/discovery/
reset denominator, retained bytes and allocation. Preserve invariants, outcomes,
DDL/reuse cleanup and independent relational/ownership review.

Accept only repeatable benefit without unexplained regression. Failed/no-benefit
candidates are rejected or reverted with evidence; do not certify them as gains.
Publish exact source, commands, individual samples, clean checkpoint result and
annotated tag in the ledger. Formal MariaDB claims remain under tic-9c58.
