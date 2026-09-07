---
id: tic-35c3
status: in_progress
type: task
priority: 2
assignee: blater
delivery: documentation
base-commit: f61c150ddb7c9c8153f1ae4503d2f29a1c14e96d
branch: ticket/tic-35c3-readme-current-limits
tags:
    - documentation
created: 2026-09-07T19:31:52.46172Z
---
# Refresh README capabilities and current limits from implemented behavior

Update the repository README to describe current master capabilities, verified limits, and remaining release and operational gaps. Keep historical alpha release notes unchanged.

## Acceptance Criteria

Numerical limits traced to production owners; current delivery status distinguished from release history; build examples use --no-daemon; local links and diff checks pass.

## Validation and review

The lead checked SQL/client behavior and current release evidence. A bounded,
independent Luna source check verified numerical limits against SqlShapeLimits,
SqlTypeDescriptor, HeapPage, TableSchemaStorage, IndexedTableLimits, and
SqlBlockRowStore. Existing SqlTypeDescriptorTest and SqlBlockRowPagedStoreTest
cover expanded VARCHAR descriptors and materialization beyond 65,536 rows.

Corrected the stored-row limit to 16,216 bytes and VARCHAR descriptor limit to
65,535 Unicode scalar values, with the stricter table-schema byte admission
explained. Removed the old materialized-store cap; documented result-row,
index, resource, network, and JDBC limits. Added delivered UNION behavior and
separated current master from historical alpha.2 notes and unfinished release
gates. Rewrote the prose for plain language and kept technical terms where
needed. All direct Gradle examples use --no-daemon.

Local Markdown targets, Gradle examples, ticket validation, and git diff checks
pass. This changes documentation only; no builds, tests, TPS runs, or slopmark
runs were needed. Historical release and compatibility documents are unchanged;
the README explicitly identifies their older numeric limits.
