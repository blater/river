---
id: tic-615d
status: in_progress
type: story
assignee: blater
parent: tic-bf0b
delivery: code
base-commit: 56bc2ecff4d3967e1c6a51ba5df08205776c13d4
branch: ticket/tic-615d-instance-identity
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
certificate with one local `BouncyCastleProvider` object passed explicitly to
EC key generation, signer, converter, certificate parsing, and signature
verification; never install/select it globally. Treat `token-sha256` and the
security manifest as credential-equivalent, and implement the exact owned
buffer/authenticator destruction lifecycle and honest public-JSSE cleanup.
Implement only the ADR-enumerated path calls with parent file-key revalidation,
same-parent regular-file staging, both-parent directory-move force, and matching
hard-link alias recovery. Require every SDS regular-file/lock channel to be a
public `FileChannel` before force/tryLock. Add real code/tests plus used
module/settings and dependency-policy entries; do not add the application
distribution yet.

## Acceptance Criteria

Partial first publication is recoverable only before instance authority exists;
accepted missing or mismatched material fails closed; no implicit regeneration
or arbitrary non-empty-directory adoption occurs. Under-lock new/torn/stale
pre-bootstrap lock recovery is limited to the otherwise empty authority-free
tree; bootstrap/instance cases require their identity and absent process. Exact
nonce-staged bootstrap and every properties checksum pass forced-write/rename/
directory-force fault tests, including the bound `.instance-<nonce>.stage` in
`DATADIR`. A partial bound instance stage is removed/recreated only after exact
path/type/owner/mode, stable file-key, and non-alias proof; unbound, aliased,
wrong-type, or changed/wrong-file-key objects are preserved.
POSIX/no-follow/provider tests cover overriding ACLs, the sole
fixed-component path-based directory create and revalidation race, file-key
swaps, immutable hard-link target/source force and alias recovery, cross-parent
directory force, SDS-returned `FileChannel` force/tryLock, and probed atomic
exclusive/replacement/force API semantics; the runtime probe makes no durability
claim. Implement the canonical qualification-record parser and runtime-observable
tuple/probe matcher without claiming to observe its evidence-only fields.
Unsupported stores including
APFS/NFS/FUSE and capabilities fail closed, while the
qualified default-Linux local ext4/xfs adapter passes. X.509 provider
selection, expiry, manifest, config-loader, River-owned secret zero/destroy on
every path, provider-key destroy false/throw `IO_FAILURE`, public session
enumeration/reference clearing, format-bound, fault, and permission tests pass.
