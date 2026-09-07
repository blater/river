---
id: tic-37c1
status: in_progress
type: investigation
priority: 2
assignee: blater
delivery: evidence
base-commit: 188012ceeaa09003b76224861def08ab06effa4e
branch: ticket/tic-37c1-test-value-audit
tags:
    - testing
    - maintenance
created: 2026-09-07T19:59:51.840471Z
---
# Audit test value duplication and fragility across River

Use three Luna high-effort audits and lead cross-layer review to identify tests to delete, merge, or rewrite without losing meaningful correctness coverage. Record concrete retained coverage and bounded follow-up work.

## Acceptance Criteria

Prioritized findings identify exact tests and retained invariants, distinguish observed failures from structural fragility, and include explicit keep decisions for unique failure/recovery coverage. No test deletion or runtime savings is claimed without validation.


## Result

Completed three Luna high-effort scoped audits and lead cross-layer review.
The accepted findings, rejected deletion suggestions, retained coverage, and
implementation boundaries are in
[the audit](../delivery/evidence/2026-09-07-test-value-audit.md).
Raw reports and historical timing inventory are retained at
`/private/tmp/river-test-value-audit-evidence-20260907`. No tests were removed
or run. Ticket/link/diff validation passes.
