# Erebor overflow reclamation and capacity progress

Date: 2026-09-29 UTC. Branch: `feature/tic-erebor-clustered-row-store`.
The implementation follows accepted design [ADR 0015](../../adr/0015-clustered-relational-row-store.md).

An overflow row replacement or delete stages the reference-removal commit
sequence in the old overflow page. The selected old page and its durable
generation are retained until a checkpoint writes that marker, the oldest
visible snapshot reaches the removal sequence, and no pinned pre-removal leaf
or overflow frame can still use it. At most one eligible page is freed during
an overflow-changing descriptor suboperation. The expanded logical WAL
suboperation records its exact page ID, generation and retirement sequence;
replay applies the same free-stack update before tuple mutations and registry publication.
Normal inline primary and secondary reads never enter this scan.

Focused tests passed:

- `RelationalDescriptorRowPathTest.retiredOverflowWaitsForOldLeafThenReusesAfterCheckpoint`:
  a held old scan reads the original 13,600-byte value after replacement;
  checkpoint returns `RETRY` while it is active. After release and checkpoint,
  the next overflow mutation frees and reuses the old page ID
  with a higher durable generation. A copy of the durable files taken before
  close flushes the live database reopens through WAL replay and preserves all
  current rows, including both post-checkpoint inserts.
- `RelationalDescriptorRowPathTest.oneLogicalMutationChangesMoreThanSixtyThreeOverflowPages`:
  one commit creates 70 distinct overflow pages and all 70 rows survive reopen.
- `RelationalDescriptorRowPathTest.overflowMutationRejectsInsufficientStagedPageBudgetBeforePublication`:
  the same 70-row mutation under a 60-page budget returns
  `RESOURCE_EXHAUSTED`, then a new transaction sees neither endpoint row.
- `IndexedPageCacheEvictionTest.tupleFreePageCanBeReidentifiedWithinAndAcrossPreparedMembers`:
  free-stack allocation consumes a page staged free in the same member and
  one freed in an earlier prepared member, incrementing durable generation.
- `IndexedPageCacheEvictionTest.oldLeafAndOverflowPinsPreventReferenceReclamation`:
  a retained prior leaf generation and a direct overflow pin each block the
  reclamation predicate until their exact pins are released.
- `IndexedRelationalWalCodecTest.tupleValueAndKeyBoundarySurviveLogicalWalRoundTrip`:
  the reclaimed page ID, generation and removal sequence survive encode/decode.
- `IndexedRelationalWalCommitTest.concurrentHybridSessionsShareOneForceAndRecoverIndependentDecisions`:
  two different rows on the same tuple leaf receive value replacements in one
  grouped force. With that force held, an earlier unchanged clustered row
  completes a read-only transaction while a changed row waits for durability.
  Both replacement values survive a WAL-only reopen and a second reopen.
- `IndexedRelationalWalCommitTest.valueGrowthSplitKeepsOldLeafAndIndependentReadDurableDuringHeldForce`:
  a value-only update grows from 8,000 to 10,000 bytes and replaces the root
  while the group force is held. The root's membership sequence stays fixed,
  a pinned old cursor reads its original 8,000-byte row, an unchanged second
  row completes a durable read, and the changed row waits for force. Both
  current rows survive WAL-only reopen.
- `IndexedPageCacheEvictionTest.pinnedOldGenerationReportsPressureThenProgressesAfterRelease`:
  a held older tuple page generation keeps a two-frame cache from publishing
  another generation. Publication returns `RETRY`, preserves the old bytes,
  then succeeds once the old pin is released.

`./gradlew --no-daemon :river-engine:test` passed after the final pre-allocation
reclamation order, frame-owner extraction and prepared-member test. The
focused crash-image recovery test also passed with the first post-checkpoint
overflow insertion reclaiming and reusing the page. The untouched-source
`slopmark` score for `IndexedPageFrameCache` was 245.463; the first reuse edit
raised it to 274.516. Moving the pin scan and frame reset to their owning
classes reduced it to 262.889. Its shallow analysis remains incomplete, so
the score is a review trigger, not a correctness result.

The crash-image test covers WAL replay of a reclamation decision and later
reuse without a page flush. The value-growth test covers the held-force root
split and an old pinned leaf. Write-path costs and an independent final
durable-format review remain before promotion.
