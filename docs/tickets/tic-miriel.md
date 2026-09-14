---
id: tic-miriel
status: closed
type: investigation
priority: 1
assignee: blater
parent: tic-carcharoth
delivery: evidence
evidence:
    - docs/tickets/tic-miriel.md
tags:
    - performance
deps:
    - tic-eowyn
    - tic-bracegirdle
    - tic-telemnar
    - tic-twofoot
created: 2026-09-13T11:46:48.655709Z
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

### Integrated disposition, 2026-09-14

Accepted subset: only tic-twofoot, delivered and published at 27033027 with
annotated perf-checkpoint-20260913-result-bitmap. Eowyn, bracegirdle and telemnar
are explicitly rejected, not shipped. No candidate production class from
45c694cc enters this integration. Current master 3cfe00e7 has the same accepted
production classes; subsequent commits record dispositions only.

The accepted mechanism proof remains the unchanged allocation test: control
4,800,000 bytes per 100,000 warmed command/row cleanup pairs, candidate <=256
bytes. The corrected bit/column units retain one 64-bit word at the existing
small-column budget and release zero-lane or genuinely excessive capacity.
All 32 affected API tests, erasure/reuse/lease checks and policy checks passed;
the accepted clean checkpoint accounts for 1,942 passing tests and 18 expected
skips. Independent Astra approved ownership, source and allocation evidence.
See tic-twofoot and its durable evidence for complete commands, failures,
class-load reconciliation and accepted build details; no repeated full build
is needed for this evidence-only integration.

Its eight matched Java TPS controls/candidates pass invariants, checkpoint,
retry accounting and empty transaction/lock/waiter cleanup. The initial short
TPS dip did not persist in the longer interleaved series. No throughput gain
is claimed; accepted benefit is the removed witnessed steady-state allocation.
Fresh unchanged-runtime external sample/all controls in tic-da4e and all eight
telemnar comparison runs pass the real five-family workload and owned-server
cleanup. These external TPS numbers are never compared with the Java workload.
Independent execution_admission_review approves the rejection decisions and
bounded current-source profile conclusion. The integrated execution outcome
is the accepted bitmap correction plus those explicit no-code dispositions.
