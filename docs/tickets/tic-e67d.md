---
id: tic-e67d
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverdCommandParser

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverdCommandParser.java`. Baseline slopwatch score: **255.590**.

## Approach

Review `RiverdCommandParser.parseStart`, `RiverdCommandParser.parseOperation`, `RiverdCommandParser.duration` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Delivery evidence

One parser owns command admission and trailing-help handling. Start and lifecycle
option grammars use shared scalar parsing and the existing command catalog.
The temporary route/forwarding layer was removed. Root and Sol/high reviewed
status, diagnostic, help-topic and default behavior. Regression tests cover both
renewal datadir forms, successful help with no diagnostic, and stop diagnostics.

Source `efb91d9b` includes accepted master `94470fab`; implementation fix is
`f19d63c3`. All 67 server-app tests passed without skips/failures/errors, as did
`:river-server-app:check` and JVM distribution installation. Final scores:
parser **15.866**, start grammar **27.720**, operation grammar **5.000**, scalar
support **14.240**, changed test **0** (original parser **255.590**).

The epic's light JVM workload, version `tic-e67d-efb91d9b-jvm`, passed at
**292.45 TPS**, p99 **68.878ms**, 484 retries, zero failed/unknown outcomes,
passing invariants and graceful cleanup to inactive. Server start took 1.327s;
stop took 1.061s. This is consistent with the adjacent accepted 296.56 TPS
control and earlier short-sample variability; no speed improvement is claimed.

Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_024105_828fa65a`.
