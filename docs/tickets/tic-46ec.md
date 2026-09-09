---
id: tic-46ec
status: closed
type: investigation
priority: 1
assignee: blater
parent: tic-9c58
delivery: evidence
evidence:
    - /Users/blater/src/ingres/river-harness/docs/tickets/standalone-river.md
    - /private/tmp/river-harness-lifecycle-0909/runs/river_harness_20260909_161623_6478d126
tags:
    - performance
    - tpcc
    - mariadb
    - parity
    - harness
deps:
    - tic-bfca
created: 2026-09-04T15:10:08.338824Z
---
# Verify standalone River stress-workload compatibility

Run river-harness against `river server` using the public protocol client and
check setup, parameterized inserts and SELECT FOR UPDATE with the shared
workload. Recheck the previously reported INVALID_EXTERNAL_INPUT failure; do
not assume it still exists after subsequent River changes.

## Design

Start with the smallest sample that exercises setup and the affected SQL. If it
fails, identify the exact operation, expected semantics and owning repository.
Fix only demonstrated compatibility gaps; do not introduce benchmark-specific
River behavior. PostgreSQL wire support is not needed for the existing River
protocol path.

## Acceptance Criteria

A focused run passes, or a reproducible failure is linked to a concrete owner
and correction. Finish with a sample covering all five transaction families,
passing invariants and no failed or unknown outcomes. Do not report zero-TPS or
parity ratios for invalid runs. This work does not wait for the comparison
sidecar or a performance promotion campaign.

## Reproduction and cause

The standalone native run authenticated and reached admission, then rejected
parameterized INSERT and SELECT FOR UPDATE with INVALID_EXTERNAL_INPUT. The
Go adapter still encoded parameter lengths as 16-bit fields in an 8-byte header;
River's current public format uses 32-bit lengths in a 10-byte header. Returned
VARCHAR lengths likewise use 32 bits. This is a harness codec mismatch, not
missing River SQL support or a performance result. Fix the adapter and its wire
fixtures together, then rerun admission and all five families.

Failure artifact: `/private/tmp/river-harness-lifecycle-0909/runs/river_harness_20260909_160808_fe80cff2`.
The run stopped its owned server and removed its temporary data.

After correcting value lengths, the focused New-Order run passed. The first
four-worker mixed run then exposed a separate pool lifecycle defect: warmup
cancellation closed transports, but the Go driver did not implement
`database/sql/driver.Validator`. Two measurement workers received dead pooled
connections and failed during preparation. Add the standard validity hook; do
not retry in-flight statements or transactions whose outcome may be unknown.
That failed run is not usable TPS evidence.
Artifact: `/private/tmp/river-harness-lifecycle-0909/runs/river_harness_20260909_161209_d7a4f850`.

## Accepted delivery

Completed by harness commit `15ca297` on `ticket/standalone-river`. See
[tic-bfca](tic-bfca.md#accepted-delivery) for the public contract, run artifacts,
correctness results, native TPS controls and slopmark review. No River engine
change was required.
