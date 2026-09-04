---
id: tic-8ef7
status: in_progress
type: bug
assignee: blater
parent: tic-5db4
delivery: code
base-commit: 588da3f19b9b892e4ace2226a6e639a751286594
branch: ticket/tic-8ef7-clean-test-contracts
tags:
    - p0
    - build
    - test
    - architecture
deps:
    - tic-2828
created: 2026-09-04T19:40:20.526765Z
---
# Restore clean test compilation after explicit resource and protocol contracts

Restore the clean repository test gate after obsolete test callers were left on removed embedded-resource and protocol request signatures.

## Design

Migrate every River-owned test caller to the explicit resource-plan and diagnostic-correlation contracts. Share the test resource policy through one test-fixture owner rather than adding compatibility overloads or duplicating the same limits in more modules.

## Acceptance Criteria

All obsolete create/openExisting and encodeSqlRequest test calls are removed; one explicit reusable test resource fixture serves current consumers; focused affected-module tests pass; and a clean full ./gradlew test gate passes without restoring superseded APIs.
