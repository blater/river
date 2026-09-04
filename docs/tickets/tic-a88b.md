---
id: tic-a88b
status: closed
type: story
priority: 1
assignee: blater
delivery: documentation
base-commit: adccf7172e74450cf4518a561b3712c4e8927c0d
branch: ticket/tic-a88b-delivery-workflow
delivered-commit: 81f07baeb52b7dc569ad101d98333a277068309a
checkpoint-tag: workflow-ticket-v1
tags:
    - workflow
    - bootstrap
created: 2026-09-04T14:52:24.248887Z
---
# Adopt source-controlled delivery workflow

Adopt visible project ticket configuration, source-controlled tickets, the River development manifesto, and immutable ticket-to-commit linkage.

## Design

The working principles live in manifesto.md; executable agent rules remain in AGENTS.md; ticket.yaml selects docs/tickets and enforces branch and commit linkage.

## Acceptance Criteria

ticket.yaml is visible and discovers docs/tickets from nested paths; README and AGENTS.md reference the workflow; the delivery branch and commits carry this ticket ID; tk validate passes.

