---
id: tic-b1a1
status: open
type: story
priority: 1
assignee: blater
delivery: code
tags:
    - release
    - distribution
deps:
    - tic-0803
    - tic-d2e9
created: 2026-09-09
---
# Publish River releases through GitHub Actions and Homebrew

Next delivery after local stop/list. Follow the existing NQL release workflow:
`../nql/release.sh`, `../nql/actions-setup.sh`, and
`../nql/.github/workflows/release.yml`. Publish the River formula into the
existing `../homebrew-tap` project.

## Scope

- Add an ergonomic `release.sh VERSION` that updates the River version, commits
  and pushes it, tags the release, and reports the Actions release outcome.
- Add GitHub Actions builds for the supported release targets and publish the
  standalone executable assets to the tagged GitHub release.
- Update the River Homebrew formula with the matching assets and checksums.
- Reuse NQL's minimal Actions/token setup and document first-time setup.

## Done

A release can be triggered from the script, the Actions run publishes its
expected assets, and the Homebrew formula installs the correct version. Verify
help and one start/connect/stop flow from an installed release. Keep the full
platform/runtime matrix in the release workflow, not in every development ticket.

No packaging framework, descriptor machinery, signing programme, or additional
package manager is part of this ticket. O3/PGO policy uses the accepted native
build configuration; do not add performance tuning to the release task.
