---
id: tic-1f42
status: closed
type: task
priority: 2
assignee: blater
delivery: code
base-commit: 5627543e624ac5a22d35e940e1f8bf6cf506b325
branch: ticket/tic-1f42-test-cleanup
delivered-commit: eb92bc9cfaf33145370e4006c2a29d46d683e574
tags:
    - testing
    - maintenance
created: 2026-09-07T20:19:28.605886Z
---
# Consolidate duplicate test assertions and TLS fixtures

Implement only the accepted cleanup findings from tic-37c1, preserving the named survivor coverage.


## Scope and validation

Use the accepted decisions in [tic-37c1](tic-37c1.md). Test-only changes; preserve
production behavior and named coverage. The lead owns serial Gradle validation
with --no-daemon, integration, and closure. No TPS or provenance redesign.

## Delivery evidence

Removed the protocol enum-count assertion; the golden switch still rejects
uncovered kinds and checks wire bytes. Parser capacity boundaries remain in
`SqlShapeCapacityTest`; maximum identifier and trailing-input checks remain in
`SqlParserTest`. Value generation now checks explicit widths and marker bounds,
all 1,000 possible last names and 64 fixed-seed samples. Loader placeholder and
initial-line-count tests remain at their owner.

Client, JDBC and CLI now share the existing TLS contexts through the client's
Gradle test fixture. The certificate contents and context behavior are unchanged.
Hostname mismatch, authentication and public-boundary tests remain in each
module. There is no new production dependency or fixture framework.

Luna authored the cleanup; the lead reviewed survivor coverage, unchanged TLS
material and dependency placement. Validation passed in 45s:
`./gradlew --no-daemon :river-protocol:test :river-sql:test :river-bench:test
:river-client:test :river-jdbc:test :river-cli:test verifyModuleGraph`.
306 tests, zero failures/errors, two existing benchmark skips. Log:
`/private/tmp/river-test-streamline-evidence-20260907/cleanup.log`.
No production behavior changed; no TPS improvement is claimed.

Combined clean validation and integration evidence: [test streamlining delivery](../delivery/evidence/2026-09-07-test-streamlining.md).
