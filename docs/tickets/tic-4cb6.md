---
id: tic-4cb6
status: open
type: story
assignee: blater
parent: tic-761e
delivery: documentation
tags:
    - riverd
    - benchmark
    - harness
    - contract
deps:
    - tic-ed14
    - tic-d2e9
created: 2026-09-04T15:23:11.814707Z
---
# Document the standalone River consumer contract

Document how an independent harness starts and stops the installed `river`
executable in server mode (`river server`), and connects using the public River
wire protocol. The public command is `river server`, not a new `river-server`
executable. Use docs/riverd-cli.md as the command authority.

## Design

Use the published readiness/status records, endpoint and client-configuration
file paths. The harness supplies a private data directory and `--port=0`, waits
for readiness, then connects with the existing Go client using the generated
public `client.properties` contract for TLS certificate pinning and token
authentication. It stops its own instance with `river server stop
--datadir=PATH` and waits for child exit. Credentials remain in files and must
not appear in logs.

## Acceptance Criteria

The documented contract lets a consumer start, connect, execute SQL and clean
up without a River checkout, Gradle, server classpaths, implementation imports,
process scans or private security-file parsing. The existing Go protocol
adapter remains the client; JDBC remains available for Java consumers. Record
the supplied build/version label and run configuration; do not require
executable fingerprints or runtime descriptors. PostgreSQL wire support is not
a prerequisite.

## Accepted delivery

Completed by harness commit `15ca297` on `ticket/standalone-river`. See
[tic-bfca](tic-bfca.md#accepted-delivery) for the public contract, run artifacts,
correctness results, native TPS controls and slopmark review. No River engine
change was required.
