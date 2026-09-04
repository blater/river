---
id: tic-ec50
status: open
type: story
assignee: blater
parent: tic-bf0b
delivery: code
tags:
    - riverd
    - server
    - security
    - distribution
deps:
    - tic-72ea
    - tic-615d
created: 2026-09-04T15:23:11.26945Z
---
# Deliver the installable authenticated riverd start and restart path

Add river-server-app with a real foreground riverd command that owns one instance, starts authenticated TLS loopback, emits stable readiness, shuts down in order, and reopens persistent data.

## Design

The module is the composition root and enters settings, dependency policy, production-module, archive, and reproducibility checks in the same delivery. Arguments validate before mutation; the resource profile has one owner.

## Acceptance Criteria

The installed distribution handles help/version, defaults, explicit paths, loopback IPv4/IPv6, port zero, ready-file refusal, first start, authenticated SQL, SIGINT/SIGTERM, restart, persistence, and reverse-order failure cleanup without Gradle or classpath knowledge.
