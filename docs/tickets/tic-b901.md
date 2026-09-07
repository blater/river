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
    - tic-72ea
    - tic-ec50
created: 2026-09-04T15:23:11.631905Z
---
# Implement offline audit archive and credential renewal

Implement exact `riverd audit archive -D` and `riverd credentials renew -D`
stopped-instance operations under exclusive ownership.

## Design

Implement the accepted five-step audit control transition without overwrite,
preserve corrupt audit, and refuse archive for terminal `EXHAUSTED` authority.
Renew in the ADR's exact nonce-derived stage/archive names without overlap,
preserving and forcing only the prior public certificate and redacted public
manifest. Publish and force the external `renewal.intent` before creating its
namespace, then publish the new security authority from the intent-bound
`.security-<nonce>.stage` beside `security.properties`. Durably unlink old
secrets after the authority switch. Fence a running generation at `notAfter`
or before `notBefore` across listener, active/resumed sessions, application
authentication, statement admission, and ordered shutdown.

## Acceptance Criteria

Live-owner rejection, audit/archive collision, corruption preservation,
full-at-start, runtime exhaustion, and both audit control directory forces are
proved. Renewal tests cover generation overflow, every force/crash boundary
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

## Required platforms (2026-09-07)

This delivery must work on macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS.
Use the amended ADR 0014 portable contract and platform adapters; do not require
POSIX permissions, Unix signals, or SecureDirectoryStream on every platform.
Platform-specific tests must preserve the same ownership, security, durability,
and recovery outcomes. The platform support is required, not yet implemented.

## Stop boundary

Own only the two specified offline commands over the existing audit and credential state machines. No new audit engine, certificate scheme, filesystem adapter, online renewal, or background rotation. Missing component behavior returns to its existing owner.
