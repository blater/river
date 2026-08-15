# U05 selected legacy adaptation evidence

Date: 2026-08-15

Status: accepted after independent provenance and relational review

## Scope

U05 selected three historical Ingres semantic oracles and independently
rewrote only their overlapping River v1 behavior. The selected files, exact
per-file SHA-256 values, deliberate differences, and absence of a broader
compatibility promise are recorded in the
[compatibility matrix](../../compatibility/legacy-support-matrix.csv) and
[adaptation report](../../compatibility/u05-selected-legacy-report.md).

No legacy SQL fixture, expected-output canon, data file, fixture name, or
production compatibility adapter was copied.

## Provenance gate

The external source and test trees are pinned by the documented
`river-tree-sha256-v2` algorithm. It excludes only regular files whose exact
basename is `.DS_Store`; links and special files still fail closed. The explicit
offline gate reproduced:

- source `eb573e56fbe635352042cb376fcc8c51753825f228d1361b7e12d1447bba4aab`
  across 15,926 retained files; and
- tests `51b7a093fe9c650bb54f22478a12035539257f0ad825250d1207ed536eec3d3c`
  across 1,739 retained files.

The Gradle verification metadata was regenerated for the approved resolved
dependency set after the first offline attempt exposed uncached POM/BOM
metadata. `verifyDependencyLedger` then passed online and offline, and
`verifyProvenancePolicyFixtures` passed its malformed upstream, unresolved
approval, missing notice, dependency drift, metadata exclusion, and stale-tree
negative cases.

## SQL evidence

`EmbeddedRiverLegacyCompatibilityTest` passes through the public embedded SQL
API and proves:

- duplicate NULL values form one `DISTINCT` result;
- a native left join retains duplicate matching outer rows and NULL-extends an
  unmatched outer row; and
- grouped exact `SUM` with repeated-aggregate `HAVING` emits only the
  qualifying group.

The focused command was:

```text
./gradlew verifyDependencyLedger verifyProvenancePolicyFixtures \
  :river-engine:test \
  --tests io.riverdb.engine.EmbeddedRiverLegacyCompatibilityTest
```

It completed successfully with 52 actionable tasks. The separate external
snapshot command completed successfully with three actionable tasks.

## Review outcome

Independent review confirmed the revision, license notice, tree and file
hashes, SQL semantics, private test ownership, and bounded compatibility
wording. U05 adds no production runtime behavior or allocation path.
