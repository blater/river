---
id: tic-615d
status: open
type: story
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - security
    - identity
    - filesystem
deps:
    - tic-11a5
created: 2026-09-04T15:23:11.178601Z
---
# Implement incarnation-bound instance credentials and identity

Create the non-empty `river-server-app` boundary with launcher-owned atomic
instance metadata, POSIX filesystem proof, owner-only credential generations,
and the one bounded client-configuration format for first creation and strict
restart.

## Design

Bind the database incarnation, credential generation, certificate/token
digests, algorithms, principal, permissions, validity, and client
configuration. `river-client` owns the only config parser. Generate the
certificate through the ADR's public Bouncy Castle builder APIs with one
aligned dependency-verified set. Add real code/tests plus used module/settings
and dependency-policy entries; do not add the application distribution yet.

## Acceptance Criteria

Partial first publication is recoverable only before instance authority exists;
accepted missing or mismatched material fails closed; no implicit regeneration
or arbitrary non-empty-directory adoption occurs. POSIX/no-follow/provider,
format bound, X.509, expiry, manifest, config-loader, fault, and permission
tests pass.
