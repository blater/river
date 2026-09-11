---
id: tic-f737
status: open
type: bug
priority: 2
delivery: code
created: 2026-09-11
---
# Diagnose Linux CHECKPOINT failure in the backup test

The existing Linux verification workflow fails at OfflineDatabaseBackupTest.java:130,
which expects CHECKPOINT to return OK. Reproduced on unchanged stable master
372ea6dc in run 34570183010 and the Linux bridge refactor 7962048e in
run 34569955385. Both runs reached platform tests without a reported platform
failure and then failed at the same backup assertion. This predates tic-5b20;
the score campaign does not claim a successful full Linux verification.

## Approach

Capture the actual CHECKPOINT status and relevant test report on Linux, trace
the first failing checkpoint stage, and fix its owning production boundary or
test assumption. Keep this separate from the score refactors; do not mask,
skip or weaken the assertion.

## Acceptance

The focused backup test passes on Linux and macOS, followed by the existing
Linux verification workflow. Keep checkpoint durability and recovery guarantees.

Evidence: `/private/tmp/river-score-20260911/tic-5b20-linux-ci-failed.log`
and `stable42-linux-ci-failed.log`.
