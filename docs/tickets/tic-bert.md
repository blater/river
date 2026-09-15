---
id: tic-bert
status: in_progress
type: task
priority: 1
assignee: blater
delivery: code
base-commit: 64329eda629acdfce0803c1e0a5ae08e21c401be
branch: ticket/tic-bert-tuple-key-admission
links:
    - tic-gothmog
created: 2026-09-15T21:24:42.823873Z
---
# Validate tuple lock keys once per admitted operation

Remove repeated full tuple-key validation inside admitted point-lock and current-resolution operations. Preserve malformed-input rejection at operation boundaries and scan endpoints. No changes to lock semantics, metadata caching, persisted formats, or protocol.

## Acceptance Criteria

Focused malformed-key and canonical lock-identity tests; existing isolation/foreign-key tests; independent correctness review; affected-module and checkpoint checks; two matched before/after New-Order samples.

## Notes

### 2026-09-15T21:30:48Z

Prepared and implemented the first bounded task from tic-gothmog full-stack follow-up: five production files, no caches or lock-policy changes. Public point operations admit keys; internal protection and protected current resolution trust admission; scan endpoints explicitly validate before projection. Independent correctness review approved caller coverage and synchronous buffer ownership. Focused IndexedTransactionSessionTest passed; reviewer-requested read-only-view state assertion corrected for full checkpoint validation.

### 2026-09-15T21:32:52Z

Clean full checkpoint passed: ./gradlew --no-daemon clean check, 3m12s, 2,029 tests reported, zero failures/errors, 19 platform/opt-in skips. Focused and existing foreign-key, isolation, recovery, and lock lifecycle tests included. Log: /private/tmp/river-two-hotpaths/tuple-clean-check.log. Slopmark: session 38.24→38.30; scan admission 6.89→12.58 because endpoint validation moved to its owning boundary; trusted helpers remain 0. Reviewed added branch logic as existing admission responsibility, not new policy.

