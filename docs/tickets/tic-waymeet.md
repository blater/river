---
id: tic-waymeet
status: in_progress
type: task
priority: 1
assignee: blater
delivery: code
base-commit: 64329eda629acdfce0803c1e0a5ae08e21c401be
branch: ticket/tic-waymeet-binary-routing
links:
    - tic-gothmog
created: 2026-09-15T21:28:43.683539Z
---
# Use binary search for scalar B-tree internal routing

Replace BTreePage.childForKey linear separator traversal with upper-bound binary search. Preserve equality-to-right-child, signed key and namespace ordering, empty-node behavior, and existing page validation and format. No metadata cache, lock-manager, or broader index changes.

## Acceptance Criteria

Exhaustive separator/gap/edge routing tests across node occupancy and namespaces, existing split and recovery tests, independent review, affected-module and checkpoint checks, and paired New-Order samples.

## Notes

### 2026-09-15T21:30:48Z

Prepared the second bounded task from tic-gothmog follow-up. Change is confined to scalar BTreePage.childForKey upper-bound routing and focused tests. Empty/full nodes, all separators and adjacent gaps, namespace ordering, signed extremes, and equality-to-right-child behavior are covered. No tuple-tree, root-cache, lock-manager, or format changes.

### 2026-09-15T21:31:12Z

Independent correctness review approved upper-bound search, strictly ordered admitted-page invariant, all traversal callers, equality/split semantics, and read-only ownership. No findings requiring changes. Validation runs remain serialized with the tuple-key feature.

### 2026-09-15T21:36:15Z

Focused BTreePageTest and storage jar build passed in 3s. Independent review approved the strict ordering assumption already enforced at admission and unchanged namespace/equality/split behavior. Slopmark BTreePage28.3441→28.4196, with no new responsibility. Candidate binary is /private/tmp/river-two-hotpaths/routing/river and differs from stable baseline only in the storage jar. Full checkpoint and workload samples pending serialized execution.

### 2026-09-15T21:43:36Z

Clean full checkpoint passed: ./gradlew --no-daemon clean check, 3m17s, 2,029 tests reported, zero failures/errors, 19 platform/opt-in skips. Existing engine split/recovery and module-policy checks passed. Log: /private/tmp/river-two-hotpaths/routing-clean-check.log. Workload samples remain pending; only BTreePage.childForKey is changed in production.

