---
id: tic-ec50
status: in_progress
type: story
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - server
    - security
    - distribution
created: 2026-09-04T15:23:11.26945Z
deps:
    - tic-72ea
    - tic-615d
    - tic-867d
    - tic-b75d
---
# Deliver the installed authenticated riverd start and restart path

## Outcome

An installed foreground `riverd` starts a persistent database, accepts River
JDBC connections, shuts down cleanly, and reopens committed data on macOS/APFS,
Linux/ext4 and XFS, and Windows/NTFS.

## Scope

Compose the delivered instance, filesystem, audit, database, and transport
owners in `river-server-app`. Own argument/resource configuration, installed
packaging, readiness, and ordered shutdown. Follow the
[CLI contract](../riverd-cli.md) for user-facing behavior and
[ADR 0014](../adr/0014-riverd-instance-security.md) for security and recovery.
Migrate every River-owned plain listener/client caller, including JDBC, CLI,
benchmarks and tests, to authenticated configuration and delete the superseded
APIs in this delivery. Migration changes connection setup, not workload or SQL
semantics. Do not introduce temporary wrappers or optional authentication.

## Acceptance

Run installed help/version, first start, authenticated SQL, commit/restart,
port-zero readiness, conflicting starts, wrong credentials, startup failure,
and shutdown on each OS. Unix signals and Windows console shutdown enter the
same owner; forced termination follows crash recovery. Test the ADR's readiness
visibility/failure boundaries and resource/secret cleanup. Distribution and
source/compiled checks prove that no plain path remains. `tic-95e8` independently
checks the assembled delivery; `tic-9640` owns power-loss qualification.

## User experience acceptance (2026-09-07)

- `riverd start --port=9192` selects a port; the default is 9191
  and port zero selects an available port. Optional `--ip=::1` selects IPv6
  loopback; the default IP is `127.0.0.1`. These replace `--listen` and `-L`.
  Help shows these examples and the
  data-directory option, defaults, foreground behavior and shutdown method.
- Bare `riverd` prints useful brief usage: short command descriptions, a start
  example, defaults and a pointer to full help. It does not list instances.
  `riverd help` and `riverd --help` print identical full help;
  `riverd start --help` explains startup in plain language. `riverd ps` owns
  instance listing. Invalid options give a concise error and point to help.
- First start creates the credentials automatically. Startup identifies the
  generated client configuration path without displaying secrets. Explain that
  TLS authenticates the server and the instance token authenticates the client.
- Ship one short, copyable JDBC example using the generated client settings.
  Users should not need to understand certificate generation, trust stores or
  protocol handshakes to connect. State how to obtain the River JDBC driver.
- Validate the documented sequence from a fresh installed distribution: help,
  start on a chosen port, connect, commit, stop and restart to read the data.
  Use the existing lifecycle tests; do not add a separate usability framework.

Delivery priority is this usable end-to-end path. Resolve routine reversible
choices locally. Cut off unrelated investigations; only a concrete blocker to
correctness, security, required platform support or this user flow may expand
work. Do not add speculative features, metadata, review gates or documentation
ceremony. Keep reviews scoped to changed behavior and reuse existing evidence.

## Stop boundary

No filesystem adapter implementation, new credential/audit mechanism, PostgreSQL
wire protocol, remote binding, service-manager integration, installer, stop/ps
command, renewal/archive command, or benchmark comparison policy. Missing
prerequisites return to their existing owners. Stop when the installed start,
JDBC, shutdown, and restart path works; no additional admin commands join it.

## Installed launcher checkpoint — 2026-09-08

Work continues on `ticket/tic-ec50-riverd-launcher`. Its prerequisite feature
branches are composed here for integration testing; they are not promoted by
this checkpoint. Windows/NTFS qualification and audit performance acceptance
remain open.

The built `river-server-app/build/install/riverd` distribution now runs without
Gradle or a source-tree classpath. On macOS/APFS, the installed script passed
help/version and invalid-port exits, first start on port zero, generated
`jdbc:river:client-file:` configuration, SQL create/insert/commit, SIGTERM,
restart and reading the committed row. Both processes removed their matching
ready/runtime/registry records and produced no stderr errors. SIGTERM produced
the JVM's conventional process exit code 143; the ordinary command exit classes
are tested separately. This signal-exit distinction still needs reconciliation
with the CLI document before acceptance.

Validation artifacts are outside Git under
`/private/tmp/riverd-delivery-evidence`:

- `launcher-gradle-5.log`: `--no-daemon :river-server-app:test
  :river-server-app:installDist`, 38 tests passed, no skips.
- `launcher-affected-tests-2.log`: affected server, client, JDBC and CLI tests
  passed after declaring the reused TLS fixture dependency.
- `launcher-installed-fork-4/summary.json`: two installed processes, committed
  JDBC data preserved across restart, matching runtime cleanup passed.
- `launcher-installed-fork-{1,2,3}`: failed diagnostic runs retained for context.
  They exposed an eight-entry directory scan in ready-file cleanup. Cleanup now
  opens the exact filename; the regression test keeps 32 unrelated files in the
  parent and proves they survive.
- `launcher-slopmark-1.txt`: composition review snapshot. Runtime record handling
  scores 552.142; identity remains 988.965. These are review triggers, not quality
  or performance claims. Runtime parsing/publication/cleanup remains with one
  record owner; launcher lifecycle and path policy are separate consumers.

Still required: integrate and validate the shared expiry fence, preserve
ownership after a nonterminal database close, complete resource admission,
remove all River-owned plain connection paths, validate installed lifecycle
failure boundaries and required platforms, and complete the existing workload
and independent-review gates. The ticket remains open until those pass.

### Adjacent transport-migration baseline

Before further production edits, pushed checkpoint `75775e20` passed two
identical River-specific TPS samples. Both used `tools/tps-test.sh
--profile=tiny --mix=standard --terminals=4 --warehouses=1 --warmup-seconds=2
--measured-seconds=10 --seed=42`, with default serializable isolation,
no-wait-stress scheduling and the tool's unchanged explicit database resource
profile. The preceding `./make.sh` used `--no-daemon`; no build or other workload
overlapped either run.

Samples: 162.4 and 163.4 committed TPS; zero retries and errors; checkpoint,
deadlock reconciliation and performance capture all `OK`. Artifacts:
`/private/tmp/riverd-delivery-evidence/launcher-tps-before-1` and
`launcher-tps-before-2`; build log `launcher-tps-baseline-build-1.log`.
These are diagnostic baselines before plain-transport removal, not a performance
acceptance or comparison with the external harness or audit-admission workload.

### Authenticated lifecycle integration

The candidate now passes 39 app tests with no failures or skips
(`launcher-auth-app-tests-1.log`) and a repeat of the installed macOS sequence
(`launcher-installed-auth-fork-1/summary.json`). Both processes committed/read
through the generated JDBC client file, removed their matching runtime records
on SIGTERM, and produced no stderr errors. The installed test removes its
owned credential directory after collecting non-secret results.

The shared credential fence, resource admission derived from page geometry and
available heap, and retention of identity after a nonterminal database close
are integrated. Independent bounded review found no concrete shutdown/expiry
ownership defect; the app test exercises a busy database retaining its identity
until its session closes. The CLI document now distinguishes normal exits from
OS signal exits, and the distribution includes a short JDBC getting-started guide.

Production compilation passes (`launcher-auth-compile-3.log`). Client/server
fixture migration and wider tests remain in progress; failed diagnostic build
logs are retained. No new TPS acceptance or platform-completion claim is made.


Latest affected tests passed: engine 1,009, backup 1, server 42, client 16,
JDBC 46, CLI 2, app 39, and benchmark 94 active tests (two explicitly opt-in
larger runs skipped). Logs: `launcher-auth-engine-tests-1.log`,
`launcher-auth-tests-5.log`, `launcher-auth-jdbc-client-tests-5.log`,
`launcher-auth-bench-cli-tests-1.log`, and `launcher-auth-app-tests-1.log`.

Installed Linux validation passed as UID 1000 on actual ext4 and XFS using the
pinned Temurin 25 distribution image. Each filesystem passed authenticated JDBC
commit, SIGTERM cleanup, restart and committed-data read. Evidence:
`launcher-installed-linux-5.log`; earlier wrapper/setup failures remain as
`launcher-installed-linux-{1,2,3,4}.log`. The task-owned VM was stopped afterward.
Windows/NTFS execution is still awaiting hosted-run approval.

The authenticated TPS pair dropped to 8.5 and 8.1 from 162.4 and 163.4.
See `docs/performance-checkpoints.md` for the unchanged workload, artifacts,
slopmark review and pending decision about mandatory durable statement auditing.
This feature remains unpromoted and the ticket open.


### Final candidate integration checks (2026-09-08)

The clean `--no-daemon --no-parallel clean test` integration run executed
1,879 tests successfully, with 18 skips and no failures or errors (1,897 total).
The accompanying policy tasks left the overall command red: the module ledger
was corrected and `verifyModuleGraph` then passed; pre-existing source-policy
and SQL-shape violations remain. No release-check pass is claimed. Evidence:
`/private/tmp/riverd-delivery-evidence/launcher-clean-integration-1.log` and
`launcher-policy-check-2.log` in the same directory.

The migrated UPDATE trace tool built with `--no-daemon`, used generated TLS
client configuration, and completed its UPDATE/COMMIT trace successfully.
Evidence: `/private/tmp/riverd-delivery-evidence/launcher-update-trace-1.log`
and the adjacent `launcher-update-trace-1/` artifacts.

This is a feature checkpoint only. Windows execution evidence and the decision
on synchronous SQL auditing remain outstanding; do not promote or close the
standalone milestone from these results.


### TPS tooling simplification (2026-09-08)

Merged the accepted `tic-0b7e` descriptor/provenance removal into this
candidate. `./make.sh` builds ordinary runner JARs with `--no-daemon`.
The authenticated one-second smoke completed with zero errors, passing
deadlock reconciliation and performance capture, using
`--version=riverd-without-descriptors`. Evidence:
`/private/tmp/tps-simple-riverd-smoke/` and
`/private/tmp/tps-simple-riverd-build.log`. This is a wiring check, not a
performance claim or acceptance of the outstanding audit regression.
