# P01 local dependency-inventory evidence

<!-- markdownlint-disable MD013 -->

Date: 2026-08-09

Evidence class: locally enforced artifact, metadata, and reference inventory;
promoted for the current M5 reference set on 2026-08-15

Subsequent decision: the project owner, acting as qualified provenance reviewer,
approved the current dependencies, minimal metadata policy, and legacy
reference trees in
[the project-owner decision record](2026-08-09-project-owner-decisions.md).
The historical validation and limitations below describe the audited commit;
current P01 promotion follows the newer record and an independent review.

## Inventory

The provenance ledger now records every external JAR resolved by every
resolvable configuration in the 29-module build. The 18 current artifacts
cover:

- Jackson annotations, core, and databind;
- HdrHistogram;
- JMH core and annotation processing plus JOpt Simple and Commons Math;
- JOL;
- JUnit Jupiter and Platform plus API Guardian and OpenTest4J.

Each dependency row records an exact coordinate/revision, upstream URL,
location, digest algorithm, declared license, factual notice outcome, purpose,
acquisition mode, SHA-256 of the resolved JAR, and the exact project-owner
approval reference. Separate tool rows and checks cover both the Gradle binary
distribution and the checked-in wrapper JAR. The wrapper notice evidence is
the checked `META-INF/LICENSE` entry; fetched, unbundled inputs record
`not-vendored` rather than an invented legal conclusion.

No external workload dataset is present in the ledger or repository. The
ledger identifies the external workspace Ingres source and test snapshots with
the upstream URLs and last imported SVN revisions recorded in their own import
manifests. They are approved references, not vendored River artifacts or direct
compatibility promises. The River source row records the repository `LICENSE`
as notice evidence. This is a repository fact, not a conclusion about every
future release or adapted input.

## Enforcement

The root `verifyDependencyLedger` task now:

1. makes each subproject resolve and hash its own configurations in that
   subproject task's execution context;
2. unions the deterministic module reports by exact
   `group:name:version` coordinate;
3. validates every ledger row against the v2 schema, known type set, exact
   approval grammar, upstream URL, revision, digest algorithm, notice outcome,
   acquisition location, and type-specific rules;
4. fails when a resolved JAR has no ledger row or a dependency row is stale;
   and
5. rejects external classifiers/extensions and file/self-resolving dependencies
   not representable by ledger v2;
6. recomputes and compares every dependency SHA-256 before `check` can pass;
   and
7. checks the wrapper JAR and Gradle distribution rows against the repository
   JAR and wrapper properties;
8. verifies repository and embedded notice evidence; and
9. requires strict checksum-pinned Gradle verification metadata, with no trust
   bypasses, that exactly matches the independently resolved JAR set.

Gradle's checked-in `verification-metadata.xml` additionally checksum-pins the
ordinary POMs, BOMs, parent POMs, and Gradle module metadata encountered by the
current build. Gradle enforces those identities during offline resolution; the
River verifier prevents removal of the metadata file or silent divergence of
its JAR inventory from the ledger.

`verifyProvenancePolicyFixtures` runs the live parser and snapshot verifier
against negative cases for dependency-set drift, a malformed reference
upstream, pending approval, missing notice evidence, and a stale tree digest.
The cases must fail for their named diagnostic.

The separate `verifyReferenceSnapshots` task verifies approved external trees
only when they are available. It is deliberately not a dependency of `check`,
so a clean River checkout never requires an absent sibling tree. Run it with:

```text
./gradlew --offline verifyReferenceSnapshots \
  -PriverReferenceWorkspaceRoot=/absolute/path/to/workspace
```

The checked-in `river-tree-sha256-v2` implementation rejects symlinks and
special files, excludes only regular files named exactly `.DS_Store`, sorts
normalized relative paths, and length-delimits every retained path, file size,
and raw per-file SHA-256 beneath a version header.

Focused validation completed successfully with 29 module reports plus the root
verifier and the exact resolved set matching the ledger. Historical integrated
commit `d13e137` passed the earlier v1 gate. The reviewed hardening at
`d36a79e` also used the v1 tree identity. On 2026-08-15 U05 versioned the
workspace identity as v2 to exclude only regular `.DS_Store` metadata; the
explicit source/test snapshot gate and both online and offline dependency and
negative-fixture gates passed with the identities recorded below. Historical
full-gate evidence for the hardening branch remains: the
`RIVER_GRADLE_HOME=/private/tmp/river-gradle-home RIVER_GRADLE_OFFLINE=true
./verify --rerun-tasks` gate then passed both 99-task clean archive assemblies,
matched all 58 archives byte for byte, and passed the final 150-task check. The
final test reports contain 218 tests with zero failures, errors, or skips.
The committed tip `4520de0` also passed `./verify-clean-checkout` from a detached
temporary clone using only the populated offline Gradle cache. Both 99-task
archive assemblies and the final 150-task check passed there without either
external reference tree being present in the checkout.

## Remaining P01 work

- Add provenance-approved checksum-pinned fetch adapters before using external
  datasets; the present RiverBank/RiverPapers tiny fixtures are River-owned
  generated data.
- Re-run the inventory and qualified review at each milestone/release.
- Obtain an independent review of each future snapshot-algorithm or approved
  reference-set change before P01 or milestone promotion.

The earlier manual review used an undocumented row encoding and recorded
`01b6c3...31d` and `da8880...8b0`. The executable v1 algorithm identified the
approved trees as `fb4564...d3bf` across 15,926 files and `283bdc...6d84`
across 1,739 files. V2 adds a new version header and excludes only regular
files named exactly `.DS_Store`; it identifies the retained source and test
trees as `eb573e...aab` and `51b7a0...d3c`. These digest changes are explicit
algorithm migrations rather than evidence that approved reference bytes were
silently replaced.

## Promotion decision

P01 is `passed` for the current M5 reference and dependency set. The project
owner approved the policy, binary set, and external references; the executable
online/offline gates and independent U05 review closed the remaining bounded
findings on 2026-08-15. A new dependency, reference, snapshot algorithm, or
redistributed input requires a fresh ledger entry and review. P06, G0, and M0
are not promoted by this decision.
