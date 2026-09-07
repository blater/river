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
