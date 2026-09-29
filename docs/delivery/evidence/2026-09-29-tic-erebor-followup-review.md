# Erebor independent follow-up review

The amended implementation passes this independent follow-up. F1 and F2 are closed, and no new code findings were identified. The independent code-review condition is satisfied for the reviewed source, incorporating the earlier durable-format, recovery and concurrency reviews. Promotion remains blocked by the existing three-retry mixed-workload acceptance condition.

Reviewed on 2026-09-29: branch `feature/tic-erebor-clustered-row-store`, HEAD `5cfefeebe9f0ac8b87c195fad23026c58b3c6d71`, production/test source `4e08034d`, with the metadata correction at `fb9772d2`. Scope was the changes since `867f6847`, the updated handoff, linked fix evidence, and the relevant ownership and recovery callers. Earlier reports remain at `/private/tmp/erebor-promotion-review/review.md` and `/private/tmp/erebor-promotion-review-2/review.md`.

## F1 metadata ownership accepted

[Queue append and unlink](/private/tmp/river-erebor/river-engine/src/main/java/io/riverdb/engine/table/IndexedOverflowRetirementQueue.java:17) now retain the metadata operation pin while acquiring and modifying link pages. Success, corruption and resource-pressure outcomes release the pin. The overflow reclaimer and tuple graph reclaimer likewise pin allocation metadata through free-page staging and free-stack publication. The tuple provider releases its existing writable metadata borrow before the queue takes ownership, avoiding overlapping writable borrows. Pin carriers are reusable, and the fix does not change the durable format or minimum cache geometry.

The original out-of-tree corruption reproduction now passes without an externally supplied metadata pin: appending page 7 produces count 3, tail 7 and `OK` overflow validation. Previously it returned success with count 2, tail 6 and `CORRUPTION`.

The new branch tests also pass for two-frame staging spill, explicit pressure, abort/retry, drop unlinking and exact cross-owner replay. The actual descriptor reuse test passes with its production-compiled four-staging-frame configuration and recovers rows and identity locators from a captured checkpoint plus the subsequent WAL decision. This addresses the ownership failure at both the isolated queue and relational recovery boundaries.

## F2 allocation verification accepted

The retained investigation reproduced the exact 21,752-byte spike and identified late loading of `StoredTableRowIntegerFilter` and `StoredTableColumnSelection`. The paired initialized probe's batches 4 through 39 were zero. These results support the setup/class-loading explanation for the reported failure; they do not establish that every warmup allocation has the same cause.

The revised test initializes those types, measures the same helper during bounded warmup, requires consecutive zero-byte batches, and then requires five independent exact-zero verification batches per path. Verification does not retry an allocating batch or select the minimum result. The leaf-buffer identity assertions remain.

My focused run passed the point and both scan paths with zero measured verification allocation. Together with the retained three fresh-JVM passes, the investigation and source change close F2 without weakening the steady-state contract. This evidence remains limited to the exercised warmed paths.

## Independent validation

All **53 tests passed**, with zero failures, errors or skips: 52 branch tests and the original unpinned corruption reproduction. The selected classes covered retirement, overflow churn/recovery, allocation, page-cache eviction, cancelled commits and logical WAL commit/recovery. XML is retained in [test-results](/private/tmp/erebor-promotion-review-3/test-results).

`git diff --check 2ada6350...HEAD` passed, and the feature worktree remains clean. No tracked files were changed. The original reproduction was copied outside the worktree with only its obsolete external-pin control excluded; its failing-case assertions were unchanged.

I inspected the retained clean-build log and engine count artifact: the author's final checkpoint passed in 4m 51s with 1,136 engine tests and no failures, errors or skips. I did not repeat the full clean build or run a new performance workload. Existing Stock Level and physical write-cost evidence retain their original versions and limitations.

## Remaining promotion decision

Erebor remains aligned with the ticket's canonical primary-leaf row store. The fixes repair the existing ownership and verification contracts without adding another storage or commit mechanism. No further source change is requested by this follow-up review.

The strict four-worker `sample all` condition at retry limit three is still unmet: both stable and candidate exhausted deadlock retries, including repeated controls. This does not establish an Erebor-specific regression. The passing matched ten-retry diagnostic also does not meet the written three-retry condition.

Before promotion, either satisfy the existing condition or record an explicit owner-approved acceptance revision that retains the failed control/candidate evidence, retry accounting, invariants and cleanup. This review does not waive that condition. Fixing a broader pre-existing contention issue should not silently expand Erebor's scope.

The code-review approval applies to the source identified above. It is not approval to merge, tag a performance checkpoint or designate a baseline while the remaining workload condition is unresolved.

## Reproduce the original regression check

From `/private/tmp/river-erebor`, with no competing build or workload:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-erebor ./gradlew --no-daemon \
  --project-cache-dir /private/tmp/river-project-cache-erebor \
  --init-script /private/tmp/erebor-promotion-review-3/tests.gradle \
  :river-engine:test \
  --tests io.riverdb.engine.table.ReviewRetirementPressureTest
```

The command now passes. The temporary source and Gradle init script are retained beside this report.
