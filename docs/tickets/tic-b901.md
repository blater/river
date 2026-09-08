---
id: tic-b901
status: open
type: story
priority: 1
assignee: blater
parent: tic-2109
delivery: code
tags:
    - riverd
    - security
    - audit
    - recovery
deps:
    - tic-ec50
created: 2026-09-04T15:23:11.631905Z
---
# Implement credential renewal; defer SQL/security audit archive

Implement `riverd credentials renew -D` as a stopped-instance operation under
exclusive ownership. The SQL/security audit archive command is removed from the
CLI contract and this ticket; no archive placeholder or near-term audit study
is planned.

## Design

Renew in the ADR's exact nonce-derived stage/archive names without overlap,
preserving and forcing only the prior public certificate and redacted public
manifest. Publish and force the external `renewal.intent` before creating its
namespace, then publish the new security authority from the intent-bound
`.security-<nonce>.stage` beside `security.properties`. Durably unlink old
secrets after the authority switch. Fence a running generation at `notAfter`
or before `notBefore` across listener, active/resumed sessions, application
authentication, statement admission, and ordered shutdown.

## Acceptance Criteria

Renewal tests cover generation overflow, every force/crash boundary
before and after authority switch, archive/stage identity collision, durable
secret deletion, same-parent security-stage recovery and safe
retry/cleanup. A partial intent-bound security stage is removed/recreated only
after exact path/type/owner/mode, stable file-key, non-alias, old-authority, and
ordered-prefix proof; unbound, aliased, wrong-type, or changed/wrong-file-key
objects are preserved. Tests cover running validity failure with
new/active/resumed TLS sessions and admission races, exit/recovery, loaded-old
`ACCESS_DENIED`, and
reload-missing-secret `IO_FAILURE`, with no silent truncation, repair, or secret
exposure. Prove exactly one wall-clock/fence read per authentication/statement,
zero warmed River allocation, and the ADR's interleaved 1/4/16-client cost
evidence; investigate every repeated shift outside adjacent-sample variation.

Any future audit/archive proposal must first present a concrete architecture
that supports neutral TPS, latency, and resource impact. Until then, audit work
is deferred and has no active prerequisite or study.

## Required platforms (2026-09-07)

This delivery must work on macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS.
Use the amended ADR 0014 portable contract and platform adapters; do not require
POSIX permissions, Unix signals, or SecureDirectoryStream on every platform.
Platform-specific tests must preserve the same ownership, security, durability,
and recovery outcomes. The platform support is required, not yet implemented.

## Stop boundary

Own only credential renewal over the existing credential state machine. No new
audit engine, archive command, certificate scheme, filesystem adapter, online
renewal, or background rotation. Missing component behavior returns to its
existing owner.
