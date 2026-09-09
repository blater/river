---
id: tic-a51d
status: closed
type: story
priority: 1
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - cli
    - server
    - distribution
    - native
deps:
    - tic-9cfd
created: 2026-09-09T09:34:26.681345Z
---
# Ship River as one self-contained native executable per platform

## Outcome

Ship one `river` executable (`river.exe` on Windows) containing both the default
SQL client and `server` mode from tic-ed14. A user can copy that file alone and
run it without Java, Gradle, a source checkout, River JARs, a sibling lib folder,
or a separately unpacked runtime. Database, credentials and user configuration
remain ordinary runtime data outside the executable.

## Approach and scope

Start with GraalVM Native Image. It compiles Java ahead of time with required
runtime support; it does not wrap a HotSpot JVM. Produce a separate artifact for
each declared supported OS/CPU target. Required filesystem paths remain
macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS. Normal operating-system
libraries are allowed; document the minimum OS/runtime baseline and architecture
of each artifact. No universal cross-platform binary is promised.

First make one focused native compatibility build of the unified application.
Exercise TLS, JDBC driver loading, foreign-memory/foreign-function file bridges,
resource loading, shutdown hooks and persistent recovery. Confirm that native
metadata and initialization choices select the correct platform adapter and
require no bundled sidecar native libraries. Stop and report a concrete blocker
if this fails; do not expand into a custom JVM extractor or packaging framework.
A JVM fallback launcher does not meet acceptance.

Once feasible, add a straightforward native packaging task and release artifact
layout, with the local runnable result at `bin/river` (or `bin/river.exe`). Keep
ordinary compilation and tests on the JVM for fast feedback; native builds are
explicit packaging operations. Replace the old public multi-directory runtime
distributions and their callers rather than supporting parallel install modes.

External Java applications still need a separately published JDBC driver
artifact. Document obtaining it and connecting with generated client.properties;
it is not an accompanying runtime dependency of the executable.

## Acceptance and validation

- Copy only the executable to a fresh directory outside the source/build tree.
  With no Java installation required and without access to accompanying build
  outputs, exercise all help topics/aliases, version, invalid command handling,
  authenticated client SQL and server selected-port/port-zero startup.
- On macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS, demonstrate commit,
  graceful platform shutdown, restart and reading committed data. Exercise wrong
  credentials, conflicting startup and failure cleanup through the executable.
  Preserve the real filesystem, TLS and durability behavior; no native-only
  weakened path. Use independent review for any necessary security, recovery
  or foreign-call changes, confined to the touched boundary.
- Apply the complete help contract from tic-9cfd; packaging must not omit modes,
  commands, nested help topics or resources required by their help.
- Compare the native server with the equivalent JVM server using identical TPS
  workloads, seeds, isolation, durability and resource configuration. Take at
  least two baseline and candidate samples; interleave longer runs only for a
  repeated unexplained shift. No TPS improvement is required, but investigate
  repeated regression before acceptance. Record branch/meaningful variation,
  build/runtime options, individual outcomes and correctness/cleanup results.
- Reuse the existing harness/process boundary for measurements. Do not compare
  unlike tps-test and external-harness figures or add provenance descriptors.
  Use slopmark on touched production files if compatibility changes reach
  performance-critical code. Pure packaging edits need no invented score gate.
- Record supported artifact targets, build command, focused tests, native smoke
  results and performance decision. Do not close with an untested required OS
  or claim that a successful native build alone proves runtime compatibility.

## Non-goals and delivery

No new SQL features, operational commands, interactive shell, PostgreSQL wire
protocol, service manager, remote access, installer/updater, benchmark framework,
reproducible-archive comparison or release-governance project.

Use branch `ticket/tic-a51d-native-river-executable` and the normal ticket commit
trailer. Depend on tic-9cfd, which follows tic-ed14. Reuse existing lifecycle validation and external
consumer migration ownership; do not create duplicate certification tracks.

## Native compatibility progress — 2026-09-09

The macOS arm64 native credential blocker is resolved. GraalVM 25.0.4 needed
explicit registrations for six Bouncy Castle constructors used by credential
creation and reload. A working JVM lifecycle trace identified the registrations;
independent review confirmed the crypto and TLS behavior is unchanged. Temporary
diagnostics and the ineffective additional-provider build flag were removed.

A copied executable passed fresh startup, authenticated CREATE/INSERT/SELECT,
graceful shutdown, restart with existing credentials, and reading the committed
row. Both owned server processes exited and the temporary database was removed.
The native binary links only macOS system libraries and frameworks.

At that checkpoint, acceptance remained open for native performance and
Linux/Windows validation. The subsequent performance and risk decisions below
supersede that merge hold.


## CPU target and PGO experiments — 2026-09-09

Source c05e3439; branch `ticket/tic-a51d-native-compiler-tuning`.
O3 host CPU targeting averaged 140.7 versus 139.7 TPS in interleaved 60s samples.
Hardware crypto instructions were enabled, but the throughput effect was small.
User-trained PGO then averaged 158.3 versus 141.4 on adjacent native-target
controls (+12.0%). A held-out New Order/Stock Level mix improved 88.2 to 100.1
(+13.5%, one pair). Training used a different seed from both validation workloads.
All PGO validation and standalone credential/restart checks passed. Two accounted
Delivery deadlock retries occurred in one CPU-target sample; no failures occurred.

See [performance checkpoints](../performance-checkpoints.md) for individual
samples, exact configuration and retained artifacts. PGO is a supported next
packaging choice to develop; CPU compatibility remains a separate decision.
No production build defaults changed, and this does not close platform acceptance.


## Pre-alpha promotion decision — 2026-09-09

The user approved O3 and PGO and explicitly accepted merging without Linux/Windows
native lifecycle validation at this pre-alpha stage. Those platforms remain
required; their untested status must remain visible in the README and native
build guide. This exception permits current integration, not a claim that the
platforms have passed. Final build-path validation, source-policy check fixes
and matched JVM/native evidence remain required before this merge.


## Accepted delivery — 2026-09-09

The supported build produces the standalone executable with O3 and optional PGO.
The public native build guide records training, final build and platform status.
Full `check` passed without exclusions. Final interleaved 60s samples passed:
JVM 169.1 / 169.6 TPS; native O3/PGO 153.3 / 153.0 TPS. There were no failed
or exhausted transactions; one native Delivery retry reconciled with the server.
The copied-file credential/SQL/restart smoke and `tools/tps-test.sh` passed.

Validation exposed an existing prepared-page predecessor ownership bug. A focused
regression reproduces its publication `INVARIANT_BROKEN`; the reviewed fix pins
the predecessor until linking or cancellation. Snapshot and cancellation tests
pass. The brittle build-time hot-method inventory and SQL match ceilings were
removed as requested; global checks remain. See the final evidence in
[performance checkpoints](../performance-checkpoints.md).

Closed under the user's explicit pre-alpha Linux/Windows validation exception
above. macOS arm64/APFS is validated; Linux ext4/XFS and Windows NTFS native
validation remain outstanding requirements, not claimed successes.
