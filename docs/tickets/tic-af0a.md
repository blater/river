---
id: tic-af0a
status: open
type: story
priority: 1
assignee: blater
parent: tic-723f
delivery: code
tags:
    - performance
    - tpcc
    - p2
    - payment
    - protocol
deps:
    - tic-00e1
created: 2026-09-04T15:10:07.903798Z
---
# Collapse Payment to one program request with a paired A/B

Implement the accepted full Payment mapping through the existing transaction-program protocol and remove the superseded chatty River-owned path.

## Design

Reuse shared typed-value ownership and execution; require exactly one EXECUTE_PROGRAM request per attempt; preserve the P0 isolation contract and every Payment branch.

## Acceptance Criteria

Semantic differential tests and rollback/retry tests pass; interleaved JDBC/program A/B reports requests, bytes, CPU, allocation, latency, and TPS; expansion to other families occurs only if the predeclared mechanism result justifies it.

## Notes

### 2026-09-04 ten-terminal architecture priority review

This is the first P2 direct throughput hypothesis because Payment is 43 percent
of the standard mix and retains hot warehouse and district locks across a
chatty JDBC path. Request count alone is not success. The paired A/B must show
the predicted reduction in Payment lock residence, client/server service cost,
or latency and TPS. If only the request counter moves, do not expand transaction
program work to another family.
