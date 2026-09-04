---
id: tic-e6c5
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-9c58
delivery: evidence
tags:
    - performance
    - tpcc
    - harness
deps:
    - tic-46ec
created: 2026-09-04T15:10:08.429494Z
---
# Route stress-workload compatibility defects to their owners

Route each evidenced compatibility gap to one repository without adding
target-specific semantics to River or coupling comparison code to
river-harness.

## Design

If River lacks required SQL or protocol semantics, create a concrete River code
story under the relevant subsystem epic. If river-harness is wrong, link its
own repository ticket and commit. If artifact comparison is missing, link the
external sidecar ticket. This coordination ticket owns no cross-repository code.

## Acceptance Criteria

Every defect has one owner, ticket, and immutable delivery or evidence
reference. River-harness then produces valid per-engine stress artifacts
through supported lifecycle contracts; comparison eligibility is decided only
by the external sidecar.
