---
id: tic-thuringwethil
status: in_progress
type: story
priority: 2
assignee: blater
delivery: code
base-commit: 3636c7b3e62717a9854d6e1b2e0548bd9152d8f1
branch: ticket/tic-thuringwethil-localwal-complexity
created: 2026-09-16T09:56:53.601056Z
---
# Reduce code complexity below Slopmark and NPATH limits

User-directed behavior-preserving simplification: every code file Slopmark below 100 and every routine NPATH at most 100. Start with LocalWal; deliver cohesive reviewed slices without metric suppression or allocation regressions.

## Acceptance Criteria

Complete Slopmark scan satisfies requested limits; affected tests pass; durable and concurrency changes independently reviewed.

## Notes

### 2026-09-16T09:57:29Z

Owner: Codex integrator with Luna/high implementation and review. Base: 3636c7b3. Worktree: /private/tmp/river-localwal-complexity. Baseline: /private/tmp/river-slopmark-baseline.json (2180 production sources, 16 scores >=100, 195 NPATH routines >100 in 177 files). First slice: LocalWal.

### Decimal arithmetic slice — 2026-09-16

Independent Luna/high review approved the unsigned carry/high-product equivalence,
scale-reduction rounding bounds, and unchanged scratch publication behavior.
`ExactDecimal128WidePower.multiply` selects all limbs once (NPATH 274 → 36);
`ExactDecimalQuantize.apply` removes unreachable quotient-overflow branches
(132 → 42); `ExactDecimal128WideProduct.multiply` shares unsigned carry calculation
(128 → 1) and uses the JDK unsigned high-product primitive.

Validation: `:river-base:check` passed 111 tests with no failures, errors, or skips.
The 625-case limb-boundary test compares the full product with BigInteger.
Module scan including tests: 114 files, maximum score 87.7803, maximum NPATH 75.
Artifacts: `/private/tmp/river-decimal-check.log` and
`/private/tmp/river-decimal-final-slopmark.json`.
This is a structural refactor; no throughput improvement is claimed. The overall
ticket remains open. Subsequent WAL/query slices receive occasional workload checks.

### Transaction value API slice — 2026-09-16

Directory growth and memory charging now belong to the existing
TransactionValueArenaSizing owner. The borrowed CharSequence view has its own
package-private implementation, retaining its arena-owned reuse lifetime.
Comparison operand validation is named alongside the existing numeric rule.
Independent Luna/high review approved the behavior and ownership boundaries.
`:river-engine-api:check` passed 32 tests, no failures/errors/skips. Full module
scan including tests: 51 files, maximum Slopmark 96.0974 and NPATH 54.
Evidence: `/private/tmp/river-value-api-check.log` and
`/private/tmp/river-value-api-final-slopmark.json`. No performance claim.
