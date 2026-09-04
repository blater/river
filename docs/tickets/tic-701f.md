---
id: tic-701f
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-ef07
delivery: evidence
tags:
    - workflow
    - tickets
    - worktree
    - coordination
created: 2026-09-04T16:49:14.008667Z
---
# Verify worktree-safe Ticket claim leases

Track delivery in the Ticket repository of atomic claims shared by every worktree of one Git repository.

## Design

Store live lease state under the Git common directory rather than source-controlled ticket files; record owner, ticket, base, branch, worktree and recovery proof; never couple Ticket to River or benchmark types.

## Acceptance Criteria

A linked Ticket release proves concurrent claim exclusion, explicit release/recovery, stale-owner diagnosis without silent expiry, and compatibility with source-controlled docs/tickets; River adopts the pinned release.
