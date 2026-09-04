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
| Loopback protocol service | `river-server` `LoopbackRiverServer` owns listener, connection slots, request lifecycle, cancellation, and server-resource shutdown. It never owns or closes the database. | Reuse as a transport adapter. The future app composition owner must close listener before database. `river-server` must not become that composition root or gain a concrete engine dependency. |
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
| Authenticated TLS-only production listener | Partially present but not enforced. `startAuthenticated` binds TLS 1.3 to the JVM loopback address. Public `LoopbackRiverServer.start` still opens a plain socket. | `tic-ec50` must expose only authenticated riverd start. The current DAG cannot satisfy the broader no-fallback source gate; the sequencing contradiction below is an explicit `tic-11a5`/backlog decision. No compatibility wrapper remains after the corrected migration gate. |
| TLS/authenticated client-only production path | Partially present but not enforced. `RiverClientConnection.connectLoopback`, nullable `SSLContext` in `RiverClientConnector`, `RiverDataSource.clearAuthentication`, `RiverDriver.openLoopback`, and `RiverSqlConnection` select a plain path. CLI token-file mode uses the JVM default trust store rather than an instance-pinned certificate. | `tic-615d` publishes validated credential/configuration material, but no ticket names ownership of JDBC/CLI client-file loading and plain API deletion. `tic-11a5` must assign that owner and dependency before closure. Tests may use explicit providers, not a public insecure production adapter. |
| Loopback address validation including host, IPv4/IPv6, port zero | Partial. Server binds `InetAddress.getLoopbackAddress()` and validates port, but there is no `-L` parser or exact accepted/rejected host contract. Clients hard-code `localhost`. | `tic-11a5` ratifies; `tic-ec50` parses before mutation and reports the selected address/port. |
| Durable audit with bounded byte admission and archive | Current behavior is fail-closed but prototype-shaped: an integer record count sets a convenience capacity; open scans every record; every authentication/authorization record takes the object monitor, writes 40 bytes, and synchronously forces metadata. No byte budget, group force, reservation, archive, or runtime recovery instruction exists. Audit may be disabled by choosing the plain listener. | `tic-a221` defines the state machine; `tic-72ea` replaces `SecurityAuditLog` completely with resource-accounted durability; `tic-b901` owns offline archive. `tic-11a5` makes audit mandatory for riverd. |
| Ready stdout/file contract with paths, endpoint, protocol, PID, certificate digest | Absent. `TpccServerMain` emits only port/sentinel data and is benchmark-specific. | `tic-ec50` produces the real records; `tic-4cb6` publishes the stable external consumer contract; `tic-95e8` checks false-readiness and failure cleanup. |
| Runtime record, exact stop, no PID scan/SIGKILL | Absent. Current TPS stop-file coordination neither identifies a production instance nor signals a verified recorded process. | `tic-0803` owns exact-process stop after the executable lifecycle exists. |
| Per-user bounded registry, `riverd ps`, multiple instances | Absent. | `tic-d2e9` owns registry/listing after exact stop. Invalid records remain diagnosable and are never signalled or silently deleted. |
| Ordered foreground lifecycle and idempotent shutdown | Partial components exist: the server has bounded shutdown; `TpccServerMain` closes server before database. There is no instance lock/registry/PID/ready lifecycle owner or JVM signal contract. | `tic-ec50` owns the one composition lifecycle; `tic-95e8` proves startup/restart/interruption; `tic-9640` runs the operational/recovery matrix. |
| One launcher-owned development resource profile | Engine request/compiler exists; only benchmark code currently duplicates concrete defaults and many flags. | `tic-ec50` adds one app owner and only public `--maximum-connections`. Do not copy `TpccServerMain` flags/default policy. |
| External harness starts installed executable and imports no River internals | Not deliverable yet. The River repository has no installed executable. | `tic-4cb6` publishes, `tic-bfca` verifies the external repository migration, and `tic-45a7` certifies the prerequisite. River does not implement cross-database comparison. |

The remaining plan requirements are inventoried explicitly here so later
stories cannot interpret a broad row above as permission to omit them:

| Plan section | Required contract and current status | Named owner or decision gate |
| --- | --- | --- |
| 4.1 commands | `start`, `stop`, `ps`/no arguments, `audit archive`, `version`, brief/comprehensive help variants, defaults, side-effect-free help/version, and invalid-syntax exit 2 are all absent. | Exact vocabulary/statuses: `tic-11a5`; implementation: `tic-ec50`, `tic-0803`, `tic-d2e9`, and `tic-b901`. |
| 4.2 paths and writes | The precise instance tree, normalized/rejected paths, pre-mutation input validation, loopback host forms, connection bound, ready-file option, and restriction to instance/registry/explicit-ready-file writes are absent. | Ratify in `tic-11a5`; implement start-owned parts in `tic-ec50`, registry in `tic-d2e9`, and verify in `tic-95e8`/`tic-9640`. |
| 4.3 credential construction | Raw 32-byte token, P-256/SHA-256 leaf, `CA=false`, `serverAuth`, digital-signature use, three SANs, exclusive stage/force/rename/directory-force publication, manifest-last authority, and renewal archive are absent. | `tic-615d` and `tic-b901`, after `tic-11a5`. |
| 4.3 credential validation | Regular-file/no-symlink, owner, mode, size, hostname/SAN, digest, algorithm, principal, permission, validity, incarnation and generation checks; strict no-regeneration restart; owner-only platform proof; and pinned client-file consumption are absent. | `tic-615d`; JDBC/CLI consumption owner is currently missing and must be named by `tic-11a5`. |
| 4.4 instance isolation | Distinct data directories/ports, lock conflict isolation, bind collision isolation, exact default instance, and no implicit most-recent/stop-all fallback are absent. | `tic-ec50`, `tic-0803`, and `tic-d2e9`; real-process proof in `tic-95e8`/`tic-9640`. |
| 4.5 listing | Digest-named bounded regular-file records, fixed registry directory, strict content/liveness validation, sorted output, warning-without-delete, no process scan/signal, and exact empty message are absent. | `tic-d2e9`; operational proof in `tic-9640`. |
| 4.6 readiness | The full ordered UTF-8 key/value set, flush-before-ready, protocol value from its owner, control-character rejection/encoding, stdout/stderr split, atomic no-overwrite ready file, and no-secret fields are absent. | `tic-11a5` ratifies exact output; `tic-ec50` implements; `tic-4cb6` publishes the consumer contract. |
| 4.7 stop | Bounded runtime record, PID/start-instant/executable/datadir verification, stale/reused/malformed rejection without signal, bounded wait, no automatic SIGKILL, matching-owner removal, and stale replacement only under lock are absent. | `tic-0803`; `tic-9640` proves real process behavior. |
| 4.8 audit archive/capacity | Validate/force/content-name/no-overwrite rename/new forced file, corruption preservation, configured byte headroom, full-at-start failure, runtime pre-admission pressure, and no rollover/truncation are absent. | State machine: `tic-a221`; persistence: `tic-72ea`; offline operation: `tic-b901`; recovery proof: `tic-9640`. |
| 5 identity | Versioned bounded identity, secure non-zero incarnation, initial generation, atomic file publication, owned empty/new database rules, exact create/open selection, lock, and no inference/repair/delete fallback are absent. | `tic-615d` and `tic-ec50`; failure proof in `tic-95e8`. |
| 6 lifecycle | Foreground ownership order, shutdown-hook installation point, idempotent normal/signal owner, reverse-order startup cleanup, listener-before-database shutdown, dual-status reporting, and no data deletion are absent as one composition. | `tic-ec50`; real interruption/restart proof in `tic-95e8` and `tic-9640`. |
| 7 resources | Engine request compilation exists, but the one public app profile and derivation from maximum connections do not. Benchmark defaults/flags must not move into riverd. | `tic-ec50`. |
| 8 structure | No app package/classes exist. Parsing, persistence, lifecycle, rendering, registry, and resource policy have no app owners. | `tic-ec50`; use concrete package-private owners and only real provider seams. |
| 9 build/distribution | Application plugin/name/main, `installDist`, root assemble, dependency declarations, production lists, archive/reproducibility and policy fixtures are absent. | `tic-ec50`; distribution evidence in `tic-95e8`. |
| 10.1 argument tests | Every help/default/path/address/port/no-argument/conflict/control-character/unknown-input case is absent. | Focused `tic-ec50` tests and `tic-95e8` evidence. |
| 10.2 identity/ownership tests | Atomic identity/restart, ready-file refusal, corrupt identity, unowned directory, locks, registry, exact stop/timeout, and preservation cases are absent. | `tic-615d`, `tic-ec50`, `tic-0803`, `tic-d2e9`; compose in `tic-9640`. |
| 10.3 security/audit tests | Complete bundle/restart/partial publication, file validation, wrong token/cert/host/replay/omission, Java/Go pinning and erasure, no-secret scans, full/archive/corruption, and source/bytecode no-plain-fallback proof are incomplete or absent. | `tic-615d`, `tic-72ea`, `tic-b901`, and the corrected diagnostics/no-fallback predecessor selected by `tic-11a5`. |
| 10.4 real lifecycle tests | Installed start/readiness/authenticated SQL/stop/restart/persistence, two instances/ps, occupied port, engine-open failure, and interruptions are absent. | `tic-95e8` and final `tic-9640`. |
| 10.5 build/performance review | Targeted app test/install/policy tasks, affected tests, integration verification, and slopmark before/after evidence have no executable app target yet. | Each implementation ticket records focused gates; `tic-95e8` and `tic-9640` record integration evidence. |
| 11 migration | External executable option, readiness parse, TLS exporter/token auth, executable fingerprint/PID, exact stop, harness source-launch deletion, and secure preservation/replacement of River-only diagnostics are not delivered. | `tic-4cb6`, `tic-bfca`, and `tic-3f57`; the current dependency contradiction must be repaired first. |
| 12 completion | None of the complete installed lifecycle, exact output, safe failure/stop/listing, build policy, or harness deletion conditions is currently met as a riverd capability. | Aggregate gate `tic-45a7`, only after its dependency chain closes. |
| 13 deferred | PostgreSQL transport, non-loopback/multi-principal service, daemon/service/privilege work, remote administration, automated data management, benchmark diagnostics in riverd, and broad tuning CLI remain out of scope. | `tic-11a5` must preserve these deferrals without using them to defer mandatory TLS, audit, credentials, or lifecycle behavior. |

Two additional current-source gaps affect ownership:

- `river-jdbc/README.md` claims instance-directory, `client.properties`, pinned
  certificate/token file and secure URL support that production
  `RiverDataSource`/`RiverDriver` do not implement. `tic-11a5` must assign one
  ticket to make documentation and code agree; the drift is not evidence of a
  delivered client contract.
- The root maximum dependency allowlist permits `river-server -> river-engine`
  even though the edge is undeclared and the plan forbids that composition.
  `tic-ec50` must narrow this dormant permission while adding the real
  `river-server-app` dependency edges and compile-visibility checks.

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

The current dependency graph cannot satisfy the no-plain-path gates as written:
`tic-3f57` depends on `tic-95e8`, although the plan assigns the source/compiled
no-fallback proof to the distribution security tests and `tic-3f57` is the
ticket that secures or replaces the plain diagnostic launcher. `tic-9640` also
requires no unauthenticated path but does not depend on `tic-3f57`. `tic-11a5`
and the backlog must select one non-cyclic order before implementation—for
example, make secure diagnostic migration a predecessor of the broad no-plain
gate, or explicitly narrow the earlier gate to the installed app and make the
full source gate depend on `tic-3f57`. This inventory does not choose or hide
that contract change.

After that repair, the named owner must change all River-owned callers and
delete plain APIs together. `TpccServerMain` may remain only while it is
authenticated and only until `tic-3f57` replaces its JFR, lock/deadlock,
WAL/commit, resource, phase, and workspace-fingerprint evidence. It is never a
supported external lifecycle.

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
