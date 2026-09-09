---
id: tic-4ec3
status: open
type: story
priority: 1
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - server
    - cli
    - jdbc
    - network
    - security
deps:
    - tic-9cfd
created: 2026-09-09T10:38:11.271289Z
---
# Support authenticated remote connections from CLI and JDBC

## Outcome

A user can start River on an explicitly selected network interface and connect
from another machine using the River CLI or JDBC. Local default start/connect
remains simple. Use the same server, sessions, authentication and transaction
behavior for local and remote connections. Future wire-protocol listeners must
be able to reuse the endpoint, identity and authentication boundaries without
reimplementing their policy; this ticket does not implement another protocol.

## User workflow and scope

- Keep the default listener on 127.0.0.1. Explicit `river server start
  --ip=192.168.1.10 --port=9191` selects a remote-reachable interface. Support
  IPv4 and IPv6; wildcard listening, if supported, must also be explicit.
- Report the resolved data directory, bound address/actual port and usable
  client connection information at startup. Distinguish a wildcard bind address
  from the address clients should use; never advertise 0.0.0.0, ::, or loopback
  as a remote destination. Specify an explicit advertised endpoint only where
  the bind address cannot supply one; avoid redundant required settings.
- Preserve automatic instance credential creation. Provide a straightforward
  way to obtain and securely transfer/import a client connection profile containing
  the endpoint, server trust information and the client's authentication material.
  It must work on the receiving machine without editing server-local absolute
  paths, constructing certificates or configuring a JVM trust store.
- Remote CLI accepts an explicit local connection-profile path. External JDBC
  uses the existing client-file connection workflow and its published driver.
  Keep default bare `river` behavior for the local default instance.
- Never export the server private key. Do not expose tokens in command arguments,
  logs, help, startup summaries or public readiness files. Explain in plain
  language that the client profile/authentication material grants access and
  must be transferred privately. Reuse the existing credential/file-permission
  owner instead of adding a second secret-storage scheme.
- Extend hierarchical help for every added/changed command and option, including
  relevant defaults, binding versus client endpoint, examples and error guidance.
  A short two-machine CLI example and a copyable JDBC example must be sufficient.
  State the ordinary prerequisite: the chosen port must be reachable through
  the user's network/firewall; do not modify firewall rules automatically.

## Architecture and security boundary

Extend the existing listener/client transport and identity owners. TLS and
server identity verification remain mandatory, as does token authentication.
Define how the remote IP or DNS endpoint is verified against the expected server
identity (including certificate pinning/rotation behavior). Do not accept all
certificates, disable identity checks or fall back to plain transport.

Keep listener address selection, public client endpoint, server identity and
client profile handling outside SQL and transaction internals. Remove the
superseded loopback-only admission rule from its owning boundary and update all
River-owned consumers/tests together; the default binding remains loopback.
Use concrete shared owners required by CLI/JDBC now. Document their future wire
consumer, but do not add unused protocol interfaces or speculative abstractions.

Reuse existing connection/resource limits, timeouts and shutdown behavior.
Disconnects must release owned resources and retain existing transaction and
commit-outcome semantics; do not silently replay a transaction with an unknown
commit outcome. Remote connectivity does not weaken durability.

## Acceptance and validation

- Demonstrate real separate-machine remote CLI and JDBC connections: start,
  authenticate, execute SQL, commit, disconnect/reconnect and read persisted data.
  A loopback-only test is not remote acceptance evidence. Cover IPv4 and IPv6
  on supported platforms; record any genuinely unavailable test environment.
- Verify default startup is still loopback-only and normal local CLI/JDBC use
  passes. Invalid bind addresses, occupied ports and unusable advertised
  endpoints produce clear errors before successful readiness.
- Verify wrong credentials, an unexpected server certificate/identity, malformed
  profiles and plaintext connections are rejected. A valid imported profile
  must work without access to the server's filesystem.
- Exercise a dropped connection during work and around commit, connection-limit
  pressure, resource cleanup and graceful server shutdown. Use focused existing
  transport/session tests rather than duplicating the database correctness suite.
- Run affected module tests with --no-daemon and independent boundary/security
  review of the changed trust path. Compare matched local tps-test.sh samples
  before/after to detect regression; remote latency is measured separately under
  a stated network setup. Use slopmark on touched production paths to check
  responsibility growth. No cross-network TPS equivalence claim is required.

## Non-goals and delivery order

No PostgreSQL wire implementation, multi-user SQL authorization, account service,
credential-renewal redesign, public-CA automation, service installer, NAT traversal,
cloud deployment system or TLS/authentication bypass. Add only the minimal profile
transfer/import operation required for the working remote client workflow; it
must reuse the existing credential owner and fit the unified command hierarchy.

Follow the current unified-command/help/native-executable work. The functional
dependency is tic-9cfd; running after tic-a51d is the delivery sequence, not a
reason to couple networking implementation to native packaging. Use branch
`ticket/tic-4ec3-remote-connections` and the normal ticket commit trailer.
