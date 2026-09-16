---
id: tic-bert
status: open
type: task
priority: 1
assignee: blater
delivery: code
base-commit: 64329eda629acdfce0803c1e0a5ae08e21c401be
branch: ticket/tic-bert-tuple-key-admission
links:
    - tic-gothmog
    - tic-waymeet
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

### 2026-09-15T21:36:14Z

Implementation commit: 929323f6. First matched sample New-Order controls (READ COMMITTED, 1 worker/warehouse, seed42, retries3, warmup20s/duration30s, durable WAL, GraalVM25 -Xmx1g, same TCP/TLS harness): baseline 379.327/374.757 TPS, server CPU2.436/2.506ms per commit; candidate375.450/372.925 TPS, CPU2.531/2.582ms. All passed, no retries/failures/unknowns, invariants and cleanup passed. This repeated CPU increase is a regression signal: integration is pending a longer same-workload control/candidate check. Captures and commands: /private/tmp/river-two-hotpaths/. Installed candidate differs from baseline in exactly the five intended engine classes.

### 2026-09-15T21:40:35Z

Longer confirmation (same configuration except duration60s): baseline375.709TPS/serverCPU2.381ms versus initial candidate350.893TPS/2.649ms; both correctness-passed. The repeated regression is retained, not discarded, and initial implementation929323f6 is NOT accepted for integration. A narrower revision restores IndexedTransactionSession and IndexedTupleKeyProtection to baseline, retains admission in existing protection owner, and removes the initial/downstream duplicate scans plus trusted projection scans. Net three production files; current unique path still reduces five full validations to one. No claim about the cause of the initial runtime regression.

### 2026-09-15T21:45:31Z

Independent correctness review approved the narrower three-production-file revision. Public protection APIs retain their baseline admission; current.unique admits through protectShared and only successful protection reaches trusted downstream resolution. Malformed current-key calls now clear the result carrier before returning INVALID_EXTERNAL_INPUT; production callers consume it only on OK. Existing synchronous buffer ownership and retained lock copies are unchanged. Read-only buffer-state test checks the actual passed view.

### 2026-09-15T21:46:59Z

Narrowed revision passed clean full checkpoint: ./gradlew --no-daemon clean check, 3m12s, 2,029 tests, zero failures/errors, 19 skips. Log /private/tmp/river-two-hotpaths/tuple-revised-clean-check.log. Installed revised candidate /private/tmp/river-two-hotpaths/tuple-revised/river differs from baseline in only IndexedTransactionTupleScans, IndexedTupleCurrentResolution, and IndexedTupleLockKey classes. Final acceptance samples follow this exact revision.

### 2026-09-15T21:51:10Z

Final narrower revision da1096b2 remains unaccepted for integration: candidate389.22TPS/2.406msCPU, interleaved unchanged control383.48/2.474, then candidate362.39/2.687 (all20s/30s). Both candidate samples passed correctness, but the repeat regression remains unexplained. No production changes beyond the requested three-file mechanism are planned. The branch is retained for review, not presented as a performance win. Routing tic-waymeet is evaluated independently on original stable64329eda.

### 2026-09-15T21:52:44Z

Independent performance review agrees to hold integration. The bad revised sample worsened warmup, median/tail latency, server CPU and client CPU; ~20ms collector boundary offsets cannot explain the shift. Host/JVM variability remains a hypothesis, not exoneration. Do not average good and bad runs into acceptance. A future focused discriminator is an identically profiled baseline/candidate pair separating compilation/GC/background CPU from full request stacks; no broader production optimization is authorized by this ticket.

### 2026-09-16T08:28:56Z

CPU follow-up identified a concrete warmup-boundary confound, not an established intrinsic regression from removing checks. Four identically profiled 20s/60s runs crossed control/candidate with both connection modes. Replacement connections: control 2.444ms CPU/commit (compiler ~0.231), candidate 2.458 (~0.227); reused connections: candidate 2.338 (~0.127), control 2.379 (~0.139). Physical socket ports and Java thread IDs confirm reuse/replacement. Fresh sessions trigger 88/93 deoptimizations in the first two measured seconds versus 0/1 when reused. Harness timeout cancellation can discard the warmup connection, leaving measurement to execute cold session paths and recompile. Same-candidate compiler CPU explains about 84% of its 0.1196ms/commit increase; carrier CPU stays near 1.73–1.76ms/commit. All runs passed, zero retries/failures/unknowns, invariants and cleanup passed. Independent review supports this attribution for the new profiles, while preserving uncertainty about old unprofiled outliers and any code effect on reconnect probability. Candidate remains open/held pending a comparison with consistent connection behavior across phases; no production merge. Full methods, individual results, report IDs, limits and next bounded acceptance step: docs/performance-checkpoints.md, 2026-09-16 tuple CPU attribution. Artifacts: /private/tmp/river-bert-cpu/.

### 2026-09-16T08:54:06Z

Accepted for integration by explicit user instruction after identifying the warmup-connection replacement confound and fixing the external harness (7d91f4f). Integrating the unchanged reviewed three-class candidate da1096b2. Prior clean full checkpoint (2,029 tests, zero failures/errors, 19 skips) applies to these exact production bytes; no new speedup claim is made. Prior adverse samples and attribution limits remain recorded. This becomes the baseline for the requested tic-waymeet recheck.
