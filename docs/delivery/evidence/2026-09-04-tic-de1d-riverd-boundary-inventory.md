# `tic-de1d` riverd authentication and launcher inventory

Status: candidate evidence for independent boundary, security, architecture,
and operations review

Evidence class: source inventory only; no production behavior or contract is
accepted by this document

## Revisions and method

The production inventory is pinned to clean `master` commit
`0f7916153eeca3d3062f10c6588c7c4d6fb66bf8`. The ticket claim is commit
`1a77bf7d6989f749ac5cb7d94d5e1cbef9743de9` on
`ticket/tic-de1d-boundary-inventory`; that commit changes only ticket metadata.
The working tree was clean before the claim. The inventory compared every
section of `docs/plans/riverd-standalone-server-plan.md` with the source,
settings, dependency policy, tests, ADR index, and delivery tickets at the
pinned revision.

The standalone plan is `Status: proposed`. `docs/adr/README.md` nevertheless
indexes `0014-riverd-instance-security.md` as Accepted, but that file is absent
from every local or remote branch. That is a broken authority reference, not an
accepted decision. `tic-11a5` must reconcile the index, plan, and actual ADR
before implementation treats the riverd security/command contract as ratified.

## Existing authoritative owners

| Capability | Current owner and evidence | Inventory decision |
| --- | --- | --- |
| Embedded create/open/close | `river-engine` `EmbeddedRiver` accepts an explicit `DatabaseResourcePlanRequest`, database incarnation, WAL generation, transaction bound, and caller-owned result. `river-engine-api` owns `RiverDatabase`. | Reuse through the public API. The launcher may compose it but must not decode engine storage or move engine implementation types into client/server contracts. |
| Loopback protocol service | `river-server` `LoopbackRiverServer` owns listener, connection slots, request lifecycle, cancellation, and listener-before-database separation. | Reuse as a transport adapter. It must not become the launcher composition root or gain a concrete engine dependency. |
| TLS and token proof | `river-server` has an authenticated TLS 1.3 entry point; `river-protocol` owns token proof and TLS exporter binding; `river-client` implements hostname verification and erases proof/binding buffers. | Reuse the protocol mechanics after launcher-owned credential/config validation. Caller-supplied `SSLContext` and token bytes are not an instance credential lifecycle. |
| Permission enforcement and audit-before-admission | `RemoteSessionAuthorizer`, `SessionEndpoint`, and the engine `SessionAuthorizer` contract audit authentication and permission decisions before session/statement effects. Accepted ADR 0012 owns this boundary. | Preserve the ordering and single permission-policy owner. Replace only the audit persistence/admission mechanism through `tic-a221`/`tic-72ea`. |
| Current audit persistence | `SecurityAuditLog` owns a 40-byte version-1 record, sequential checksum/validation, fail-closed corruption, fixed record capacity, and `RESOURCE_EXHAUSTED`. | Useful behavioral evidence, not the scalable riverd audit design. Each append is synchronized and performs `CONTENT_AND_METADATA` force before advancing the record count. |
| JDBC/client adapters | `river-client`, `river-jdbc`, and `river-cli` already propagate native status or JDBC `SQLException`, have authenticated variants, and erase temporary credential buffers. | Keep codecs/status mapping. Migrate production-facing construction to bounded client-configuration files; delete optional-plain selection from the supported production path. |
| River diagnostic launcher | `river-bench` `TpccServerMain` owns TPS-specific resources, readiness sentinel, JFR, metrics, and stop-file coordination. | Retain temporarily only for `tools/tps-test.sh`, secure it under the same TLS/auth boundary, and delete it only after `tic-3f57` replaces every accepted diagnostic producer. It is not riverd. |

## Requirement-to-owner inventory

| Standalone-plan requirement | Pinned-source status | Owning delivery and migration/deletion boundary |
| --- | --- | --- |
| Installed `riverd` executable in production module `river-server-app` | Absent. The module is not in settings, dependency policy, archive policy, or source. No application distribution exists. | `tic-ec50` creates the functioning module and distribution after `tic-11a5`, `tic-72ea`, and `tic-615d`; `tic-95e8` proves the installed artifact. Do not add empty scaffolding. |
| Side-effect-free command parsing, help/version, exit/status mapping | Absent. | `tic-11a5` ratifies exact outcomes; `tic-ec50` implements them in the app boundary. Version must come from the distribution, not Git/source inspection. |
| `-D` layout, exclusive instance lock, first-create versus strict restart | Absent above the embedded database. `EmbeddedRiver` can create or open only when a caller already supplies identity and resource inputs. | `tic-615d` owns atomic launcher identity/credential persistence; `tic-ec50` owns orchestration. Never infer identity from database bytes, adopt arbitrary non-empty state, or fall back from open to create. |
| Incarnation-bound token, self-signed TLS leaf, manifest, client configuration, renewal | Absent. Tests provide `SSLContext` and token externally; no generator, manifest, permission/symlink/owner validation, pinned config loader, or renewal exists. | `tic-615d` owns creation/restart identity and client config; `tic-b901` owns stopped-instance renewal. Secrets remain file-owned and never enter argv, URLs, readiness, registry, logs, or audit. |
| Authenticated TLS-only production listener | Partially present but not enforced. `startAuthenticated` binds TLS 1.3 to the JVM loopback address. Public `LoopbackRiverServer.start` still opens a plain socket. | `tic-ec50` must expose only authenticated start. `tic-95e8` must prove no compiled production fallback. Plain entry points and all River-owned callers are deleted together once diagnostics are migrated/secured; no compatibility wrapper remains. |
| TLS/authenticated client-only production path | Partially present but not enforced. `RiverClientConnection.connectLoopback`, nullable `SSLContext` in `RiverClientConnector`, `RiverDataSource.clearAuthentication`, `RiverDriver.openLoopback`, and `RiverSqlConnection` select a plain path. CLI token-file mode uses the JVM default trust store rather than an instance-pinned certificate. | `tic-615d` publishes validated file paths; `tic-ec50`/client-owned follow-up migrates CLI, JDBC, and client callers. Delete nullable/optional production authentication and default-trust construction when all owned callers move. Tests may use explicit providers, not a public insecure production adapter. |
| Loopback address validation including host, IPv4/IPv6, port zero | Partial. Server binds `InetAddress.getLoopbackAddress()` and validates port, but there is no `-L` parser or exact accepted/rejected host contract. Clients hard-code `localhost`. | `tic-11a5` ratifies; `tic-ec50` parses before mutation and reports the selected address/port. |
| Durable audit with bounded byte admission and archive | Current behavior is fail-closed but prototype-shaped: an integer record count sets a convenience capacity; open scans every record; every authentication/authorization record takes the object monitor, writes 40 bytes, and synchronously forces metadata. No byte budget, group force, reservation, archive, or runtime recovery instruction exists. Audit may be disabled by choosing the plain listener. | `tic-a221` defines the state machine; `tic-72ea` replaces `SecurityAuditLog` completely with resource-accounted durability; `tic-b901` owns offline archive. `tic-11a5` makes audit mandatory for riverd. |
| Ready stdout/file contract with paths, endpoint, protocol, PID, certificate digest | Absent. `TpccServerMain` emits only port/sentinel data and is benchmark-specific. | `tic-ec50` produces the real records; `tic-4cb6` publishes the stable external consumer contract; `tic-95e8` checks false-readiness and failure cleanup. |
| Runtime record, exact stop, no PID scan/SIGKILL | Absent. Current TPS stop-file coordination neither identifies a production instance nor signals a verified recorded process. | `tic-0803` owns exact-process stop after the executable lifecycle exists. |
| Per-user bounded registry, `riverd ps`, multiple instances | Absent. | `tic-d2e9` owns registry/listing after exact stop. Invalid records remain diagnosable and are never signalled or silently deleted. |
| Ordered foreground lifecycle and idempotent shutdown | Partial components exist: the server has bounded shutdown; `TpccServerMain` closes server before database. There is no instance lock/registry/PID/ready lifecycle owner or JVM signal contract. | `tic-ec50` owns the one composition lifecycle; `tic-95e8` proves startup/restart/interruption; `tic-9640` runs the operational/recovery matrix. |
| One launcher-owned development resource profile | Engine request/compiler exists; only benchmark code currently duplicates concrete defaults and many flags. | `tic-ec50` adds one app owner and only public `--maximum-connections`. Do not copy `TpccServerMain` flags/default policy. |
| External harness starts installed executable and imports no River internals | Not deliverable yet. The River repository has no installed executable. | `tic-4cb6` publishes, `tic-bfca` verifies the external repository migration, and `tic-45a7` certifies the prerequisite. River does not implement cross-database comparison. |

## Current insecure and duplicate paths to remove

The following are migration boundaries, not compatibility promises:

1. Plain `LoopbackRiverServer.start` and its unauthenticated `SessionEndpoint`
   construction remain reachable production APIs.
2. Plain `RiverClientConnection.connectLoopback` and the nullable-context branch
   in `RiverClientConnector` remain reachable.
3. JDBC permits an unset or cleared authentication configuration, and the CLI
   explicitly selects plain operation when no TLS context is supplied.
4. `TpccServerMain` constructs `EmbeddedRiver`, resource defaults, a plain
   server, readiness, metrics, and shutdown in a second composition root.
5. The standalone plan's proposed launcher policy is partly duplicated by the
   benchmark shell/Java launcher but has no production owner.

`tic-ec50` must change all River-owned production callers with the API removal.
`TpccServerMain` is the one temporary exception owned by `tic-3f57`; its removal
condition is replacement of JFR, lock/deadlock, WAL/commit, resource, phase, and
workspace-fingerprint evidence. While retained, it must be authenticated and is
not a supported external lifecycle.

## Audit cost and failure semantics observed

`SecurityAuditLog.append` is synchronized across all connections. It encodes
one reusable direct buffer, writes the record, then calls
`file.force(CONTENT_AND_METADATA)` before the authorizer admits the action.
This correctly avoids a successful unaudited admission, but serializes every
authentication and permission check behind an individual filesystem force.
The record-count bound returns `RESOURCE_EXHAUSTED` before append; an I/O/force
failure also prevents admission. The implementation does not distinguish a
definite pre-force failure from an ambiguous force outcome, reserve cumulative
bytes, batch equivalent ordering, or provide archive/backpressure recovery.
Those are contract questions for `tic-a221`, not changes in this inventory.

## Worktree and delivery ownership

The review found no uncommitted production work at the pinned revision.
`tic-de1d` owns only this evidence and its ticket file. `tic-a221` runs in the
disjoint `/private/tmp/river-tic-a221` worktree and owns its separate evidence.
Because Ticket v0.5.0 lacks repository-wide worktree leases, the lead integrator
created and pushed both claims serially and remains the only promotion owner.
This is a controlled coordination workaround, not closure evidence for
`tic-701f`.

## Acceptance conclusion

Every standalone-plan requirement above has a present owner/status and named
migration or deletion boundary. The source is reusable at the embedded,
transport, protocol, authorization, status, and client-codec boundaries, but
there is no riverd distribution or supported lifecycle. The ADR index
contradiction, mandatory audit design, instance credentials, and exact command
outcomes must be resolved before implementation. This evidence changes no
production behavior and is ready for independent review; it does not accept
ADR 0014, close `tic-11a5`, or authorize harness promotion.
