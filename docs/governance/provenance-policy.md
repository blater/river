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

## Reference Material boundary

The project owner approved the workspace reference source and historical tests as
reference material for River functionality, kernel design, test suites,
and kernel tests. Contributors may inspect and adapt ideas or behavior from
those references in creating specifications and plans. 

### Chinese Wall

The reader, whether human or agent, may inspect approved outside code or
documentation and produce a River-owned feature description, plan, contract,
specification, or test intent. That reader must not implement the resulting
feature or component. A different human or agent must perform the
implementation, and the implementer must not have access to the original
outside code or documentation, its excerpts, screenshots, transcripts,
diffs, copied tests, or other extracts. The handoff must contain only the
River-owned requirements and decisions needed to implement the behavior.

This is a mandatory Chinese wall, not a recommendation or a declaration that
can be waived for convenience. The same separation applies when the roles are
held by humans, agents, or a human/agent pair. If the implementer has already
seen the outside material, a new implementer without that access is required;
the original reader may review the result only through the independent review
process and may not supply reference-derived patches.

Outright copying is expressly forbidden. This includes verbatim copying,
mechanical translation, line-by-line or structure-preserving ports,
transcription, renaming-only rewrites, copying or adapting source tests and
fixtures as implementation inputs, and copying distinctive text, code,
layouts, or documentation. Ideas, documented behavior, and independently
derived requirements may inform a new River design, but River artifacts must
be written independently from the handoff and must not reproduce the outside
expression.

A change that uses outside material identifies the source path/version and
records whether the result is a new River design or a behavioral adaptation.
The provenance record also identifies the reference reader/specifier and the
independent implementer, and confirms that the implementer did not access the
outside material. A missing separation or attestation blocks implementation
acceptance.

### Historical compliance clarification

The Chinese wall has been followed rigorously for River work to date. The
failure to state that requirement in this repository's provenance
documentation was a documentation error, caught only during the 2026-08-21
audit. The isolation process was nevertheless enforced by overriding
instructions maintained outside this repository. The omission was therefore
not a waiver of the wall or a failure to apply it; this policy now records the
requirement explicitly and makes the existing practice auditable.

Approval to read the references does not create a direct compatibility promise
or waive attribution and notice obligations. River's accepted product, SQL,
format, and API profiles remain authoritative. 

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
