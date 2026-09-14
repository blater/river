---
id: tic-nimloth
status: closed
type: bug
assignee: blater
parent: tic-primula
delivery: code
base-commit: 3cfe00e7a1d5817a70308d106c4f27d2eba71a14
branch: ticket/tic-nimloth-retry-safety
delivered-commit: 7acf689a165910cde528cedb121ab99cb13cbbf6
tags:
    - safety
    - protocol
    - transactions
created: 2026-09-13T16:13:56.814975Z
---
# Preserve terminal transaction failures after failed rollback

The user withdrew the 15-second policy and its implementation on 2026-09-14.
There is no maximum runtime, response-silence deadline, transport replacement,
startup rewrite, or whole-epic admission gate in this ticket.

The remaining scope is the existing rollback/retry correctness correction in
local commit `1e463e6d`: preserve a terminal primary failure when rollback fails,
and prevent replay when failed rollback cannot establish a clean transaction.
Successful rollback retains the existing retry behavior. This change still needs
integration; it makes no performance or CHECKPOINT kernel-safety claim.

The startup changes from `ce7645e1` are withdrawn in the branch working tree.
The eight unintegrated TLS draft files were removed from source and archived at
`/private/tmp/river-withdrawn-scope-20260914-zz5y0kd6/` with the prior ticket notes.
Their tests are historical evidence of a rejected design, not delivery requirements.

Existing focused validation of the retry correction: nine focused tests and
99 benchmark-module tests passed, with two opt-in skips; source/module policy
checks passed. The original kernel failure remains under tic-osgiliath.

### Scoped delivery validation, 2026-09-14

The production correction and test are byte-identical to reviewed 1e463e6d,
rebased as the sole code mechanism onto stable 3cfe00e7. Independent
execution_admission_review reconfirmed terminal cause preservation, non-retryable
failed rollback and unchanged successful-rollback retry behavior. No deadline,
startup, TLS or shutdown source is included.

Fresh affected-module validation passes: 99 benchmark tests, including all nine
retry tests, with two existing opt-in skips. The same no-daemon Gradle invocation
passes ten retained-plan/DDL/authorization tests, 18 JDBC typed/prepared tests,
one terminal cleanup test, source policy and module graph checks, and installTps.
Total: 128 passed, two skipped; BUILD SUCCESSFUL in 18s. This correctness delivery
makes no throughput, P0 or CHECKPOINT kernel-safety claim. Exact command, XML and
logs are retained in the 20260914-performance-epics durable evidence directory.
