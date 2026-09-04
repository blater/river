---
id: tic-dd80
status: open
type: story
priority: 1
assignee: blater
parent: tic-ef07
delivery: code
tags:
    - workflow
    - tickets
    - ci
    - promotion
created: 2026-09-04T16:49:14.119496Z
---
# Enforce ticket linkage at River promotion

Make River's integration gate reject commits and delivery metadata that violate ticket.yaml rather than relying only on convention.

## Design

Invoke the pinned Ticket validator over the exact feature range and merged revision; require ticket branch form, exact trailers on non-merge commits, an existing ticket, valid dependencies, and closure metadata without introducing a second parser in shell.

## Acceptance Criteria

Focused valid/invalid range tests pass; local promotion and CI use the same owner; documented emergency bypass is explicit, audited, and cannot silently mark a ticket delivered.
