---
id: tic-ec50
status: open
type: story
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - server
    - security
    - distribution
deps:
    - tic-72ea
    - tic-615d
created: 2026-09-04T15:23:11.26945Z
---
# Deliver the installable authenticated riverd start and restart path

Complete the `river-server-app` boundary with a real foreground riverd command
and installed distribution that own one instance, start authenticated TLS
loopback, emit stable readiness, shut down in order, and reopen persistent data.

## Design

The module is the only composition root. Add application/distribution,
archive, reproducibility, and only-used dependency entries; remove the dormant
`river-server -> river-engine` allowance. Arguments validate before mutation
and the resource profile has one owner. Migrate every River server, client,
JDBC, CLI, benchmark, script, and test caller to authenticated config, including
`TpccServerMain`, then delete all plain/nullable APIs and tests in the same
delivery without wrappers.

## Acceptance Criteria

The installed distribution handles help/version, defaults, explicit paths,
loopback IPv4/IPv6, port zero, ready-file refusal, first start, authenticated
SQL, SIGINT/SIGTERM, restart, persistence, and reverse-order failure cleanup
without Gradle or classpath knowledge. It packages and enforces the fixed
qualification-record location, launcher-manifest binding, runtime-observable
matcher, and SDS public-`FileChannel` capability while leaving durability
acceptance to `tic-95e8`/`tic-9640`. With a ready file, forced atomic
publication is the sole commit and broken stdout is an irrelevant mirror;
without one, prefix/partial failures clean up but an observed complete final
ready record remains irrevocable across later flush failure. Publication races
are deterministic. A post-visibility target/parent/source force failure or
ambiguous stdout-only final failure sets terminal `IO_FAILURE`, cleans up, and
eventually exits 1; ready-file mirror failure stays nonterminal. Under-lock
stale-ready cleanup removes only an exact incarnation/owner/process/file-key
binding. Normal, failed, and signal shutdown release the listener,
workers, and JSSE before idempotent authenticator destruction; caller scratch
is zeroed on every path, public session/key cleanup failure is `IO_FAILURE`,
and no provider-owned opaque erasure is claimed. Source and compiled-code checks find no plain
listener/client fallback or optional authentication branch.
