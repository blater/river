---
id: tic-95e8
status: open
type: investigation
assignee: blater
parent: tic-bf0b
delivery: evidence
tags:
    - riverd
    - security
    - distribution
    - evidence
created: 2026-09-04T15:23:11.364586Z
deps:
    - tic-ec50
---
# Validate the installed riverd lifecycle on required platforms

## Outcome

Independent evidence that the exact installed server meets ADR 0014 on
macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS.

## Scope and acceptance

Consume the adapter and component evidence; exercise their composition through
real installed processes. Cover authenticated JDBC, restart/persistence,
conflicting ownership, invalid credentials/configuration, readiness publication
failures, interruption, process crashes, and resource/secret cleanup. Confirm
packaging works without Gradle/source-tree knowledge and no plain path remains.
Record the tested source, platform adapter, JDK/native runtime, filesystem, and
relevant storage settings. Reuse unchanged component tests with a brief impact
rationale. A new JAR checksum does not require a full platform requalification.

## Stop boundary

Evidence only: no implementation, new manifest/receipt format, test runner, or
expanded security model. Stop at the first unexplained correctness failure,
preserve it, and route the defect to its owner. Runtime probes do not establish
power-loss durability; that remains `tic-9640` work. Do not turn this gate into
another architecture or provenance review campaign.
