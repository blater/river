---
id: tic-5f87
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify WindowsFileBridge

File: `river-platform/src/main/java/io/riverdb/platform/riverd/ntfs/WindowsFileBridge.java`. Baseline slopwatch score: **102.192**.

## Approach

Build on `tic-e334`. Give Windows native bindings, status capture and call-state
storage one owner, and move the existing ACL/security-descriptor policy into a
concrete security owner. Keep actual file operations in WindowsFileBridge.
Preserve typed signatures, capture options, the single status state, UTF-16 and
security-descriptor lifetimes, and all caller ordering. Remove old forwarding
paths together; add no per-call allocation. The package-wide runtime-init rule
from `tic-e12b` covers new native owners.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-platform` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
