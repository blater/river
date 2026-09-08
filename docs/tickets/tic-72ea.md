---
id: tic-72ea
status: in_progress
type: story
assignee: blater
parent: tic-bf0b
delivery: code
base-commit: cda33bf2dc464f54b8935eb5b6c5fdef71103c9f
branch: ticket/tic-72ea-riverd-audit
tags:
    - riverd
    - security
    - audit
    - wal
deps:
    - tic-11a5
created: 2026-09-04T15:23:11.086718Z
---
# Implement resource-accounted durable security audit

Replace per-operation synchronous audit forcing with the mechanism accepted in
the `tic-a221` evidence merged at
`e592addff67ac6016ae6e9e37e3bf374a6511f0d`, while retaining fail-closed
semantics.

## Design

Keep audit policy in one `river-server` security owner; implement the exact
event, byte-reservation, group-force, exhaustion, archive-control, and recovery
state machines ratified by ADR 0014. Change every `SessionAuthorizer` caller
together; no transitional authorization wrapper remains.

## Acceptance Criteria

Authentication and statement admission, group force, crash, corruption, exhaustion, cancellation, archive, allocation, and secret-erasure tests pass; matched authenticated TPS shows the removed force mechanism without an unexplained regression.

## Stop boundary

Own only audit admission, byte accounting, group forcing, and recovery under the accepted audit design. No launcher, platform adapter, archival CLI, credentials, new audit format for convenience, or benchmark framework. `tic-b901` owns the archive command. Stop after focused failure tests and matched authenticated TPS validate this mechanism.

## Control checkpoint (2026-09-07)

The telemetry-only control preserves the existing 40-byte record format and
one force per decision. The authenticated admission runner checks allowed
reads, denied writes, prepared reads and both conditional-program branches;
it verifies audit counts, restart and denied-write effects before publishing.
The old audit supports aggregate counts only, not wire request correlation.
The artifact labels that limitation and unavailable authenticator destruction.

Validation: server module tests and `SecureRemoteJdbcGateTest` pass. The
four-client, 40-requests-per-client smoke completed all 160 requests with
208 expected and observed decisions, no capacity rejection, and no denied
write effect. Evidence: `/private/tmp/riverd-delivery-evidence/`,
`audit-runner-compile-3.log` (server/JDBC tests pass; the recorded runner compile
error was fixed), and `audit-control-smoke-5/artifact.json` plus its digest.
Slopmark: existing audit owner 78.5429; new runner 361.088, TLS helper 10.3519,
snapshot 0. The runner has no promotion/comparison policy; its size is a review
signal and no additional runner responsibilities are planned. Timed matched
measurements remain outstanding; this is not acceptance of the candidate.

Control source `f345199` was pushed before the candidate changed. Two matched
30-second samples after 5 seconds warmup were captured at each initial client
count (seed 410221, GraalVM/JDK 25, AC power):

| Clients | Sample | Requests/s | Decisions | Forces | p99.9 ms |
| --- | --- | --- | --- | --- | --- |
| 1 | 1 | 185.000 | 7069 | 7069 | 16.146 |
| 1 | 2 | 190.333 | 7272 | 7272 | 14.492 |
| 4 | 1 | 216.633 | 8307 | 8307 | 42.107 |
| 4 | 2 | 222.667 | 8532 | 8532 | 41.845 |

Each artifact is under the evidence root as `control-c<clients>-s<sample>/`,
with its digest and adjacent Gradle log. All decisions reconciled and denied
writes had no effect. The original monitor-blocked metric is **invalid**: JVM
thread management omits the server's virtual threads. Subsequent artifacts
label it unavailable; CPU is process-wide and allocation uses the total JVM
counter. Separate matched JFR recordings must supply monitor-wait evidence.
No mechanism change was present during these samples. Wider interleaved
acceptance measurements remain outstanding.

## Implementation checkpoint (2026-09-08)

The audit owner, durable-prefix group force, recovery controls and real
connection/session/request/phase correlation are now assembled on the feature
branch. Engine, server and all affected client/JDBC/benchmark setup callers
change together. SQL and workload semantics are unchanged.

GraalVM/JDK 25 compilation covered all engine/API production sources. The
separately assembled candidate passed 56 focused tests plus the changed retained
prepared execution test. The production Gradle server, client and JDBC suites
pass 35, 16 and 44 tests respectively, with no failures or skips; benchmark
compilation passes. All Gradle commands used `--no-daemon` and ran serially.
Evidence: `audit-full-candidate-compile-5.log`,
`audit-full-candidate-tests-4.log`, `audit-engine-caller-tests-1.log` and
`audit-gradle-integration-3.log` under `/private/tmp/riverd-delivery-evidence/`.

The four-client correctness smoke completed 400 requests, validated expected
denials and reopened the audit successfully. Its immutable artifact is
`/private/tmp/riverd-delivery-evidence/audit-candidate-c4-smoke-4/artifact.json`.
It proves aggregate reconciliation, not independently decoded per-request
correlation or a TPS result. Reviewed candidate sources were archived outside
the repository at `/private/tmp/riverd-delivery-evidence/audit-source-candidate/`.

Independent review and system review found and fixed program-step phase routing,
terminal-generation predecessor recovery and I/O error classification. The
terminal test now checks recovery as well as force-before-result ordering.
Slopmark: audit owner 198.026 (control 78.5429), bootstrap 307.372, control
recovery 265.068. Format/recovery and force coordination remain separate owners;
no benchmark policy entered production.

This remains an implementation checkpoint. Matched authenticated performance,
allocation evidence and the accepted wider correctness gates remain outstanding;
the ticket is not closed or promoted.

`verifySourcePolicy` is red on existing Unicode literals, verification-metadata
indentation, Darwin indentation and engine/transaction identifier checks.
Comparison against master found no new production violation from this patch.
The additional copied candidate violation was removed by archiving scratch
sources outside the checkout. Evidence: `audit-engine-policy-1.log`; engine
tests run separately because the combined invocation stopped on policy failure.
No policy rule or unrelated baseline source was changed.

The separate engine suite passed all 1,009 tests with no failures or skips in
4m53s (`audit-engine-tests-1.log`), bringing affected-module coverage to 1,104
passing tests. Independent decoding of the smoke audit found 540 checksummed
records with gap-free sequences 1–540, four authentication decisions and 536
statement decisions. All statement correlations were nonzero and 103 decisions
had positive program steps. The 540 records comprise 12 setup and 528 workload
decisions; reopening preserved that count. This does not map synthetic workload
IDs to individual wire requests.
