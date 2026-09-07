---
id: tic-bf0b
status: open
type: epic
assignee: blater
parent: tic-e1c9
delivery: none
tags:
    - riverd
    - server
    - security
    - distribution
deps:
    - tic-e2b7
created: 2026-09-04T15:22:14.708917Z
---
# R1: deliver an installable authenticated riverd

Build the real river-server-app distribution and authenticated start/restart lifecycle without Gradle or classpath knowledge at the consumer boundary.

## Design

The first module delivery contains a functioning command, not scaffolding. It composes EmbeddedRiver and LoopbackRiverServer while preserving module ownership and fail-closed security.

## Acceptance Criteria

Installed help, first start, authenticated SQL, orderly shutdown, restart, persistence, port zero, readiness, build policy, and failure cleanup pass through the real distribution.

## Required platforms (2026-09-07)

This delivery must work on macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS.
Use the amended ADR 0014 portable contract and platform adapters; do not require
POSIX permissions, Unix signals, or SecureDirectoryStream on every platform.
Platform-specific tests must preserve the same ownership, security, durability,
and recovery outcomes. The platform support is required, not yet implemented.
