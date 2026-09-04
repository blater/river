# `tic-0636` build and cooperative host-exclusion provenance

Status: **historical candidate rejected at `0d50ada30379f3829d35c37196dd234757588401`; replacement evidence pending**

This record validates the provenance mechanism on one minimal River-specific
diagnostic. It is not a performance comparison, an Alpha3 result, or closure of
the blocked `tic-1dda` P0 matrix.

## Candidate and focused coverage

The clean source was pushed as
`40947bb2f5375f6ec54fbb7d5514f3a129a4ddb4` on
`origin/ticket/tic-0636-p0-provenance`. The implementation adds a Gradle-owned
runtime-classpath descriptor, removes the build bypass and shell-owned
classpath list, and retains exact build, source, tool, JDK, Gradle-runtime,
launched-byte, host, and publication records.

`bash tools/test-tps-provenance.sh` passed 18 deterministic boundary tests.
They exercise clean and stale class output, source and classpath mutation,
missing/mismatched entries, failed build and complete log retention, idle and
busy Gradle daemons, River/harness/profile/database overlap, PID identity
races, interruption, secret-independent normalized process evidence,
immutable publication, and full checkpoint replay. Bash syntax and diff checks
passed. The focused Gradle provider task completed with all 24 declared
classpath entries present; its independently reproduced 208-line manifest was
byte-identical.

Slopmark was run before and after across the production performance modules
and `tools`. The console summaries appeared unchanged and the highest observed
score was 283.617 for `SqlSessionExecutionCoordinator`, but the raw reports
were not retained. Those observations are historical review notes, not durable
or independently replayable evidence. No production Java file was touched,
and slopmark did not score the shell files changed here.

## Rejected development attempts

The first focused Gradle invocation failed at Kotlin DSL compilation because
the Gradle home API is nullable. It was corrected before the successful
provider verification. A later development smoke at
`/private/tmp/river-tic-0636-smoke-20260904T2200Z` was interrupted before its
build after the first source-manifest implementation spawned per-file
processes across 2,676 files. Its empty output directory is not evidence and
was not accepted. The implementation replaced that path with bounded bulk
pipelines: the full source manifest then took 0.38 seconds and the real
classpath manifest 0.15 seconds locally. The hermetic suite independently
proves normal TERM interruption after the evidence lifecycle begins and
retains its build, metadata, and checkpoint records without overwrite.

A dirty-source development smoke at
`/private/tmp/river-tic-0636-smoke-20260904T2230Z` then completed and replayed,
but is diagnostic development evidence only. Its metadata SHA-256 is
`2e9237d593c32a8df56905cae7c651bd702a31b826e5c25d7d1b2804bb13dfbe`.

## Clean end-to-end diagnostic

The integrator serialized and authorized this exact command from clean
`40947bb2f5375f6ec54fbb7d5514f3a129a4ddb4`:

```sh
RIVER_TPS_GRADLE_USER_HOME=/private/tmp/river-gradle-tic-0636 \
RIVER_TPS_PROJECT_CACHE_DIR=/private/tmp/river-project-cache-tic-0636 \
tools/tps-test.sh \
  --output-dir=/private/tmp/river-tic-0636-clean-40947bb-20260904T2300Z \
  --profile=tiny --mix=payment --terminals=1 \
  --warmup-seconds=1 --measured-seconds=1 \
  --maximum-attempts=3 --seed=42
```

The run exited zero with `result=completed`, `phase=checkpoint`, `status=OK`,
102 measured commits, zero retries or errors, reconciled deadlocks, passing
performance capture, and clean terminal transaction/lock/waiter state. The
retained build log reports Gradle 9.7.0 success in 913 ms with 34 tasks (one
executed, 33 up-to-date). Build and launch used Java 25.0.4 from the same
reported Java home.

Key immutable hashes are:

| Retained item | SHA-256 |
| --- | --- |
| Metadata | `74f8fa7c9532aa0a87bf84940b2305c8da69f5afa609dfe36f61e96328eccb15` |
| Build log | `f3eb911656e056e3c4a254e6e0904d1a26ff621bceb573b971fc77c4a0a1819b` |
| Source manifest | `33e063ee7cdbcccefaf9f4e85ab5191214d81a79e61fee011797a556a1804e11` |
| Runtime descriptor | `1c030ffc4046a6705375bd68aa2f6997cc1b6e98d891b3bd96b85e316971cb18` |
| Launched classpath manifest | `bf69f81ef42494b117822537794e465f8fdbdde02962e4360d5499e77ef42d01` |
| Gradle runtime manifest | `b1eac788b5afad4fc4cfad3bc6a70b3b96a35a1866f25a4bba36fe4322485d0f` |
| Checkpoint ledger | `a5de68b76473de877be5b4090267bffdc98df6355ae493f446f9c46b0c96130f` |
| Host observations | `6926a343c7fa9cccf21ff43e8eab90971e998bad26b12c548627083d6cee44c4` |
| Host normalized processes | `1886a1e4ce37fb3e284c737804f6d79fb7f4a816185b107ded0297cdc3d46719` |
| Host classifications | `048a517ea6a8f760768bc030441a344cc42c656118789321102f71899a3be4f5` |
| Host violations (empty) | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| Acceptance artifact | `5969d5e818c03a9726a92f95138f60e6f43748e25e8857c7b86b848e747e7dce` |

Independent recalculation matched every metadata hash. Fresh source,
classpath, and Gradle-runtime manifests matched the retained versions. All
seven ledger stages (`build`, `server`, `client_start`, `client_finish`,
`result`, `publication`, and `metadata`) reproduced from 23 retained checkpoint
files. The evidence directory contains 44 files.

Seven host samples cover the build and managed workload interval, retaining
3,235 normalized PID/PPID/start/rule rows and 35 idle-daemon classifications.
No raw process argv or argv-derived digest is retained, and the violations file
is empty. The exclusive lease remained held until immutable publication.

## Review boundary

The retained host evidence is periodic process observation plus a cooperative
exclusive lease; it cannot prove that a non-cooperating process started and
exited entirely between samples. Independent operations/security review must
decide whether that residual platform limitation satisfies the promotion
contract before merge. No process was stopped by the exclusion monitor, no
secret-bearing environment or Java option value was retained, and no
`perf-checkpoint-*` tag or P0 certification is proposed here.

Independent operations/security review rejected this candidate because the
then-current contract overstated host-wide exclusion and because lease,
publication, exact Gradle-daemon identity, bounded inspection/retention, and
temporary-tree cleanup boundaries were incomplete. The hashes and paths above
remain immutable historical development evidence; they are not promotion
evidence under the corrected cooperative contract. In particular, the old
command did not record the now-required operator no-uncoordinated-work
attestation. No result in this section may be used to close `tic-0636` or
unblock `tic-1dda`.
