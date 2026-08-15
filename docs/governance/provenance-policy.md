# River provenance policy

Status: accepted by the project owner and qualified provenance reviewer

## Purpose

River is a new implementation. This policy keeps source, tests, durable-format
fixtures, datasets, and dependencies traceable while the project evaluates
ideas and behavior from prior systems.

This document is an engineering control, not legal advice. The project owner,
acting as River's qualified provenance reviewer, approved it on 2026-08-09.
That approval is recorded in the
[project-owner decision record](../delivery/evidence/2026-08-09-project-owner-decisions.md).

## Repository license

The repository currently contains the GNU Affero General Public License,
version 3, in `LICENSE`. AGPL-3.0 is the initial project license. New original
River source is contributed under that license unless a separately accepted
license-change decision defines the migration, notice, and contributor effects.

## Legacy Ingres boundary

The project owner approved the workspace Ingres source and historical tests as
core reference material for River functionality, kernel design, test suites,
and kernel tests. Contributors may inspect and adapt ideas or behavior from
those references. A change that adapts logic, tests, fixtures, messages, or
layouts identifies the source path/version and records whether the result is a
new River design, a behavioral adaptation, or copied material.

Approval to use the references does not create a direct compatibility promise
or waive attribution and notice obligations. River's accepted product, SQL,
format, and API profiles remain authoritative. Verbatim or mechanically
translated material receives explicit file-level review before merge.

## Artifact ledger

Every non-original input records:

- stable name, upstream project/dataset and source URL;
- version, commit or dataset revision;
- location, checksum, and the named digest algorithm;
- declared license and a factual notice outcome or evidence location;
- purpose and consuming module/test suite;
- whether it is vendored, generated, or fetched on demand;
- reviewer and approval reference.

The initial ledger lives in `docs/governance/provenance-ledger.csv`. Its v2
schema and exact approval grammar are executable build policy. Empty or unknown
identity, revision, license, notice, use, or approval fields block the build;
`pending` is not an approval state. A notice cell records what is present or
absent in River's current acquisition/distribution mode. It does not make a
legal conclusion about what a future distribution must include.

River checksum-locks the resolved binary JAR set twice: the ledger binds the
approved dependency inventory, while `gradle/verification-metadata.xml` also
pins resolved POM, BOM, and Gradle module metadata. Gradle verifies those files
during resolution, and the River gate rejects missing verification metadata,
trust bypasses, non-SHA-256 entries, or a JAR set that differs from the ledger.
Metadata files do not need individual ledger rows unless they are vendored,
redistributed, patched, or direct evidence for a published claim.

External workspace references use `river-tree-sha256-v2`. The verifier rejects
symbolic links and special files, excludes only regular files whose exact
basename is `.DS_Store`, sorts normalized `/`-separated relative paths, and
hashes a version header followed by a length-delimited path, byte length, and
raw SHA-256 for every retained regular file. `verifyReferenceSnapshots` runs this
algorithm only when explicitly requested because approved external trees are
not inputs to an ordinary clean River checkout. The exact byte encoding is
recorded in the
[reference snapshot algorithm](reference-snapshot-algorithm.md).

## Dependencies

- Prefer small, maintained dependencies whose license and semantic role are
  explicit.
- A library may implement mechanics behind a River-owned contract; it does not
  silently become the authority for durability, SQL, transaction, or consensus
  semantics.
- Pin direct dependency versions and wrapper distribution checksums.
- Generate and review a dependency/license inventory before each milestone
  promotion and release.
- Source or binary vendoring requires an explicit ledger entry and upgrade
  owner.

## Tests and datasets

- River-owned seeded generators are the canonical regression data.
- External datasets are pinned, checksum-verified, fetch-on-demand realism
  suites and are not committed unless redistribution is explicitly approved.
- Dataset terms govern data artifacts independently of River source licensing.
- Historical Ingres tests are approved reference inputs. River may adapt their
  semantic expectations with a provenance link; one-for-one compatibility is
  not required.

## Review gate

Every change answers whether it includes adapted logic, test cases, fixtures,
messages, layouts, or data. A positive answer links its ledger entry and
approval. Missing provenance is a blocking review finding, not a documentation
cleanup item.
