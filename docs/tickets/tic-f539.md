---
id: tic-f539
status: in_progress
type: investigation
priority: 2
assignee: blater
delivery: evidence
tags:
    - performance
    - wal
    - transactions
created: 2026-09-06T17:27:49.450704Z
---
# Measure commit queue overlap with WAL force under current host load

Quantify whether queued commits can release locks materially earlier if preparation, append and publication overlap an earlier WAL force. Capture unchanged current-host TPS baselines before probes.

## Design

Evidence-only scope. Temporary timestamp/JFR probes in existing commit owners; no scheduling, durability, workload, client or batching changes. Separate actual enqueue-to-selection delay from preflight-inclusive QUEUE_RESIDENCE telemetry. Reconcile probe events to measured capture, compare loaded-host control/probe samples, preserve probe patch externally and remove production probes before delivery. Model overlap as an opportunity bound, never achieved TPS.

## Acceptance Criteria

Two untouched short TPS baselines before edits; longer controls and repeated measured probe samples with valid outcomes; quantified force-overlap opportunity and preparation-to-publication budget; slopmark before/after; independent concurrency/recovery review of interpretation and next-step safety constraints.

