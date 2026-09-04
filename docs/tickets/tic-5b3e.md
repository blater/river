---
id: tic-5b3e
status: open
type: story
assignee: blater
parent: tic-e5ff
delivery: code
tags:
    - performance
    - tpcc
    - p1
    - transactions
    - admission
deps:
    - tic-ca05
created: 2026-09-04T15:10:07.259189Z
---
# Reserve cumulative cohort demand before physical staging

Form cohorts by cumulative admitted page, version, staging, and WAL demand rather than fixed transaction or record counts.

## Design

Reserve against compiled runtime budgets before side effects. Apply cancellable backpressure or split admission while retaining one canonical physical writer and one transaction outcome.

## Acceptance Criteria

Concurrent admission, partial-cohort failure, cancellation, overflow, cleanup, and direct/group equivalence tests pass; counters reconcile cumulative demand without arbitrary caps.
