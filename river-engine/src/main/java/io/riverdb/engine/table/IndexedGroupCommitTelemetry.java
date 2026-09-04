package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockScope;

/** Caller-owned, bounded snapshot of indexed commit-path telemetry. */
public final class IndexedGroupCommitTelemetry {
  public static final int LATENCY_BUCKETS = 64;
  public static final int COHORT_SIZE_BUCKETS = Integer.SIZE - 1;
  public static final int PREDICATE_MASKS = 1 << 5;

  private static final int PATH_COUNT = IndexedCommitPath.values().length;
  private static final int STAGE_COUNT = IndexedCommitStage.values().length;
  private static final int REASON_COUNT =
      IndexedGroupCommitIneligibilityReason.values().length;
  private static final int DIRECT_REASON_COUNT = IndexedDirectCommitReason.values().length;
  private static final int FAILURE_STAGE_COUNT = IndexedGroupFailureStage.values().length;
  private static final int STATUS_COUNT = StatusCode.values().length;
  private static final int LOCK_SCOPE_COUNT = LockScope.values().length;

  private final long[][] stageCounts = new long[PATH_COUNT][STAGE_COUNT];
  private final long[][] stageNanos = new long[PATH_COUNT][STAGE_COUNT];
  private final long[][] stageLatencyCounts =
      new long[PATH_COUNT][STAGE_COUNT * LATENCY_BUCKETS];
  private final long[] stageFailureCounts =
      new long[PATH_COUNT * STAGE_COUNT * STATUS_COUNT];
  private final long[] predicateMaskCounts = new long[PREDICATE_MASKS];
  private final long[] primaryReasonCounts = new long[REASON_COUNT];
  private final long[] directReasonCounts = new long[DIRECT_REASON_COUNT];
  private final long[] successfulCohortSizeCounts =
      new long[COHORT_SIZE_BUCKETS];
  private final long[] groupFailureCohortCounts = new long[FAILURE_STAGE_COUNT];
  private final long[] groupFailureTransactionCounts = new long[FAILURE_STAGE_COUNT];
  private final long[] groupFailureCounts =
      new long[FAILURE_STAGE_COUNT * STATUS_COUNT];
  private final long[] groupFailureTransactionStatusCounts =
      new long[FAILURE_STAGE_COUNT * STATUS_COUNT];
  private final long[] failedBeforeStatusCounts = new long[STATUS_COUNT];
  private final long[] groupLockHoldingsReleasedByScope =
      new long[LOCK_SCOPE_COUNT];
  private final IndexedCommitQueueTelemetry queue = new IndexedCommitQueueTelemetry();
  private long totalCommitSubmissions;
  private long readOnlyCommitSubmissions;
  private long writeCommitSubmissions;
  private long failedBeforeSubmission;
  private long groupableTransactions;
  private long initiallyIneligibleTransactions;
  private long groupableAdmissions;
  private long initiallyIneligibleAdmissions;
  private long attemptedGroupCohorts;
  private long attemptedGroupTransactions;
  private long successfulGroupCohorts;
  private long successfulGroupTransactions;
  private long directCommitTransactions;
  private long groupLockHoldingsReleased;
  private int maximumSuccessfulCohort;
  private boolean overflowed;

  public void reset() {
    fill(stageCounts);
    fill(stageNanos);
    fill(stageLatencyCounts);
    java.util.Arrays.fill(stageFailureCounts, 0);
    java.util.Arrays.fill(predicateMaskCounts, 0);
    java.util.Arrays.fill(primaryReasonCounts, 0);
    java.util.Arrays.fill(directReasonCounts, 0);
    java.util.Arrays.fill(successfulCohortSizeCounts, 0);
    java.util.Arrays.fill(groupFailureCohortCounts, 0);
    java.util.Arrays.fill(groupFailureTransactionCounts, 0);
    java.util.Arrays.fill(groupFailureCounts, 0);
    java.util.Arrays.fill(groupFailureTransactionStatusCounts, 0);
    java.util.Arrays.fill(failedBeforeStatusCounts, 0);
    totalCommitSubmissions = 0;
    readOnlyCommitSubmissions = 0;
    writeCommitSubmissions = 0;
    failedBeforeSubmission = 0;
    groupableTransactions = 0;
    initiallyIneligibleTransactions = 0;
    groupableAdmissions = 0;
    initiallyIneligibleAdmissions = 0;
    attemptedGroupCohorts = 0;
    attemptedGroupTransactions = 0;
    successfulGroupCohorts = 0;
    successfulGroupTransactions = 0;
    directCommitTransactions = 0;
    groupLockHoldingsReleased = 0;
    java.util.Arrays.fill(groupLockHoldingsReleasedByScope, 0);
    queue.reset();
    maximumSuccessfulCohort = 0;
    overflowed = false;
  }

  public long totalCommitSubmissions() { return totalCommitSubmissions; }
  public long readOnlyCommitSubmissions() { return readOnlyCommitSubmissions; }
  public long writeCommitSubmissions() { return writeCommitSubmissions; }
  public long failedBeforeSubmission() { return failedBeforeSubmission; }
  public long groupableTransactions() { return groupableTransactions; }
  public long initiallyIneligibleTransactions() { return initiallyIneligibleTransactions; }
  public long groupableAdmissions() { return groupableAdmissions; }
  public long initiallyIneligibleAdmissions() { return initiallyIneligibleAdmissions; }
  public long attemptedGroupCohorts() { return attemptedGroupCohorts; }
  public long attemptedGroupTransactions() { return attemptedGroupTransactions; }
  public long successfulGroupCohorts() { return successfulGroupCohorts; }
  public long successfulGroupTransactions() { return successfulGroupTransactions; }
  public long directCommitTransactions() { return directCommitTransactions; }
  public long groupLockHoldingsReleased() { return groupLockHoldingsReleased; }
  public long groupLockHoldingsReleased(LockScope scope) {
    return scope == null ? 0 : groupLockHoldingsReleasedByScope[scope.ordinal()];
  }
  public IndexedCommitQueueTelemetry queue() { return queue; }
  public boolean overflowed() { return overflowed || queue.overflowed(); }
  public int maximumSuccessfulCohort() { return maximumSuccessfulCohort; }

  public long predicateMaskCount(int mask) {
    return mask < 0 || mask >= PREDICATE_MASKS ? 0 : predicateMaskCounts[mask];
  }

  public long primaryIneligibilityCount(IndexedGroupCommitIneligibilityReason reason) {
    return primaryReasonCounts[reason.ordinal()];
  }

  public long directCommitCount(IndexedDirectCommitReason reason) {
    return directReasonCounts[reason.ordinal()];
  }

  public long successfulCohortSizeBucket(int bucket) {
    return bucket < 0 || bucket >= successfulCohortSizeCounts.length
        ? 0 : successfulCohortSizeCounts[bucket];
  }

  public static int cohortSizeUpperBound(int bucket) {
    if (bucket < 0 || bucket >= COHORT_SIZE_BUCKETS) return 0;
    return bucket == COHORT_SIZE_BUCKETS - 1
        ? Integer.MAX_VALUE : (1 << (bucket + 1)) - 1;
  }

  public long groupFailureCohortCount(IndexedGroupFailureStage stage) {
    return groupFailureCohortCounts[stage.ordinal()];
  }

  public long groupFailureTransactionCount(IndexedGroupFailureStage stage) {
    return groupFailureTransactionCounts[stage.ordinal()];
  }

  public long groupFailureCount(
      IndexedGroupFailureStage stage, StatusCode status) {
    return groupFailureCounts[statusIndex(stage, status)];
  }

  public long groupFailureTransactionStatusCount(
      IndexedGroupFailureStage stage, StatusCode status) {
    return groupFailureTransactionStatusCounts[statusIndex(stage, status)];
  }

  public long failedBeforeStatusCount(StatusCode status) {
    return failedBeforeStatusCounts[status.ordinal()];
  }

  public long stageCount(IndexedCommitPath path, IndexedCommitStage stage) {
    return stageCounts[path.ordinal()][stage.ordinal()];
  }

  public long stageNanos(IndexedCommitPath path, IndexedCommitStage stage) {
    return stageNanos[path.ordinal()][stage.ordinal()];
  }

  public long stageLatencyBucket(
      IndexedCommitPath path, IndexedCommitStage stage, int bucket) {
    if (bucket < 0 || bucket >= LATENCY_BUCKETS) return 0;
    return stageLatencyCounts[path.ordinal()][stage.ordinal() * LATENCY_BUCKETS + bucket];
  }

  public long stageFailureCount(
      IndexedCommitPath path, IndexedCommitStage stage, StatusCode status) {
    return stageFailureCounts[stageFailureIndex(path, stage, status)];
  }

  public boolean reconciles() {
    long submissions = sum(
        sum(readOnlyCommitSubmissions, writeCommitSubmissions), failedBeforeSubmission);
    long evaluations = sum(groupableTransactions, initiallyIneligibleTransactions);
    long admissions = sum(groupableAdmissions, initiallyIneligibleAdmissions);
    long failedGroups = 0;
    long failedGroupCohorts = 0;
    long predicateMasks = 0;
    long primaryReasons = 0;
    long directCommits = 0;
    long successfulCohortSizes = 0;
    long failedBeforeStatuses = 0;
    long releasedHoldings = 0;
    for (IndexedGroupFailureStage stage : IndexedGroupFailureStage.values()) {
      long stageCohorts = 0;
      long stageTransactions = 0;
      for (StatusCode status : StatusCode.values()) {
        int index = statusIndex(stage, status);
        stageCohorts = sum(stageCohorts, groupFailureCounts[index]);
        stageTransactions = sum(
            stageTransactions, groupFailureTransactionStatusCounts[index]);
      }
      if (stageCohorts != groupFailureCohortCounts[stage.ordinal()]
          || stageTransactions != groupFailureTransactionCounts[stage.ordinal()]) {
        return false;
      }
      failedGroups = sum(failedGroups, stageTransactions);
      failedGroupCohorts = sum(failedGroupCohorts, stageCohorts);
    }
    for (long count : predicateMaskCounts) predicateMasks = sum(predicateMasks, count);
    for (long count : primaryReasonCounts) primaryReasons = sum(primaryReasons, count);
    for (long count : directReasonCounts) directCommits = sum(directCommits, count);
    for (long count : successfulCohortSizeCounts) {
      successfulCohortSizes = sum(successfulCohortSizes, count);
    }
    for (long count : failedBeforeStatusCounts) {
      failedBeforeStatuses = sum(failedBeforeStatuses, count);
    }
    for (long count : groupLockHoldingsReleasedByScope) {
      releasedHoldings = sum(releasedHoldings, count);
    }
    long initiallyIneligibleDirect =
        directReasonCounts[IndexedDirectCommitReason.INITIALLY_INELIGIBLE.ordinal()];
    return !overflowed
        && totalCommitSubmissions == submissions
        && groupLockHoldingsReleased == releasedHoldings
        && writeCommitSubmissions == evaluations
        && failedBeforeSubmission == failedBeforeStatuses
        && initiallyIneligibleTransactions == predicateMasks
        && initiallyIneligibleTransactions == primaryReasons
        && initiallyIneligibleAdmissions == initiallyIneligibleDirect
        && queue.reconciles(
            admissions, attemptedGroupCohorts, initiallyIneligibleAdmissions)
        && directCommitTransactions == directCommits
        && successfulGroupCohorts == successfulCohortSizes
        && validSuccessfulGroupPopulation()
        && admissions == sum(attemptedGroupTransactions, initiallyIneligibleAdmissions)
        && groupableAdmissions == attemptedGroupTransactions
        && attemptedGroupTransactions == sum(successfulGroupTransactions, failedGroups)
        && attemptedGroupCohorts == sum(successfulGroupCohorts, failedGroupCohorts);
  }

  void copyFrom(IndexedGroupCommitTelemetry source) {
    reset();
    totalCommitSubmissions = source.totalCommitSubmissions;
    readOnlyCommitSubmissions = source.readOnlyCommitSubmissions;
    writeCommitSubmissions = source.writeCommitSubmissions;
    failedBeforeSubmission = source.failedBeforeSubmission;
    groupableTransactions = source.groupableTransactions;
    initiallyIneligibleTransactions = source.initiallyIneligibleTransactions;
    groupableAdmissions = source.groupableAdmissions;
    initiallyIneligibleAdmissions = source.initiallyIneligibleAdmissions;
    attemptedGroupCohorts = source.attemptedGroupCohorts;
    attemptedGroupTransactions = source.attemptedGroupTransactions;
    successfulGroupCohorts = source.successfulGroupCohorts;
    successfulGroupTransactions = source.successfulGroupTransactions;
    directCommitTransactions = source.directCommitTransactions;
    groupLockHoldingsReleased = source.groupLockHoldingsReleased;
    queue.copyFrom(source.queue);
    maximumSuccessfulCohort = source.maximumSuccessfulCohort;
    overflowed = source.overflowed;
    copy(stageCounts, source.stageCounts);
    copy(stageNanos, source.stageNanos);
    copy(stageLatencyCounts, source.stageLatencyCounts);
    copy(stageFailureCounts, source.stageFailureCounts);
    copy(predicateMaskCounts, source.predicateMaskCounts);
    copy(primaryReasonCounts, source.primaryReasonCounts);
    copy(directReasonCounts, source.directReasonCounts);
    copy(successfulCohortSizeCounts, source.successfulCohortSizeCounts);
    copy(groupFailureCohortCounts, source.groupFailureCohortCounts);
    copy(groupFailureTransactionCounts, source.groupFailureTransactionCounts);
    copy(groupFailureCounts, source.groupFailureCounts);
    copy(groupFailureTransactionStatusCounts, source.groupFailureTransactionStatusCounts);
    copy(failedBeforeStatusCounts, source.failedBeforeStatusCounts);
    copy(groupLockHoldingsReleasedByScope, source.groupLockHoldingsReleasedByScope);
  }

  void recordReadOnlyCommit() {
    readOnlyCommitSubmissions = increment(readOnlyCommitSubmissions);
    totalCommitSubmissions = increment(totalCommitSubmissions);
  }
  void recordFailedBefore(StatusCode status) {
    failedBeforeSubmission = increment(failedBeforeSubmission);
    totalCommitSubmissions = increment(totalCommitSubmissions);
    failedBeforeStatusCounts[status.ordinal()] = increment(
        failedBeforeStatusCounts[status.ordinal()]);
  }
  void recordWriteSubmission(int mask, boolean coordinatorAdmission) {
    writeCommitSubmissions = increment(writeCommitSubmissions);
    totalCommitSubmissions = increment(totalCommitSubmissions);
    if (mask == 0) {
      groupableTransactions = increment(groupableTransactions);
      if (coordinatorAdmission) groupableAdmissions = increment(groupableAdmissions);
    } else {
      initiallyIneligibleTransactions = increment(initiallyIneligibleTransactions);
      predicateMaskCounts[mask] = increment(predicateMaskCounts[mask]);
      int primary = Integer.numberOfTrailingZeros(mask);
      primaryReasonCounts[primary] = increment(primaryReasonCounts[primary]);
      if (coordinatorAdmission) {
        initiallyIneligibleAdmissions = increment(initiallyIneligibleAdmissions);
      }
    }
  }
  void recordAttemptedGroup(int count) {
    attemptedGroupCohorts = increment(attemptedGroupCohorts);
    attemptedGroupTransactions = add(attemptedGroupTransactions, count);
  }
  void recordSuccessfulGroup(int count) {
    successfulGroupCohorts = increment(successfulGroupCohorts);
    successfulGroupTransactions = add(successfulGroupTransactions, count);
    int bucket = 31 - Integer.numberOfLeadingZeros(count);
    successfulCohortSizeCounts[bucket] = increment(successfulCohortSizeCounts[bucket]);
    if (count > maximumSuccessfulCohort) maximumSuccessfulCohort = count;
  }
  void recordDirectCommit(IndexedDirectCommitReason reason) {
    directCommitTransactions = increment(directCommitTransactions);
    directReasonCounts[reason.ordinal()] = increment(directReasonCounts[reason.ordinal()]);
  }
  void recordGroupLockHoldingsReleased(LockScope scope, long count) {
    groupLockHoldingsReleased = add(groupLockHoldingsReleased, count);
    groupLockHoldingsReleasedByScope[scope.ordinal()] = add(
        groupLockHoldingsReleasedByScope[scope.ordinal()], count);
  }
  void recordGroupFailure(
      IndexedGroupFailureStage stage, StatusCode status, int transactionCount) {
    int stageIndex = stage.ordinal();
    int statusIndex = statusIndex(stage, status);
    groupFailureCohortCounts[stageIndex] = increment(groupFailureCohortCounts[stageIndex]);
    groupFailureTransactionCounts[stageIndex] = add(
        groupFailureTransactionCounts[stageIndex], transactionCount);
    groupFailureCounts[statusIndex] = increment(groupFailureCounts[statusIndex]);
    groupFailureTransactionStatusCounts[statusIndex] = add(
        groupFailureTransactionStatusCounts[statusIndex], transactionCount);
  }
  void recordStage(IndexedCommitPath path, IndexedCommitStage stage, long elapsedNanos) {
    long elapsed = Math.max(0, elapsedNanos);
    int pathIndex = path.ordinal();
    int stageIndex = stage.ordinal();
    stageCounts[pathIndex][stageIndex] = increment(stageCounts[pathIndex][stageIndex]);
    stageNanos[pathIndex][stageIndex] = add(stageNanos[pathIndex][stageIndex], elapsed);
    int bucket = latencyBucket(elapsed);
    int latencyIndex = stageIndex * LATENCY_BUCKETS + bucket;
    stageLatencyCounts[pathIndex][latencyIndex] = increment(
        stageLatencyCounts[pathIndex][latencyIndex]);
  }

  void recordStageFailure(
      IndexedCommitPath path, IndexedCommitStage stage, StatusCode status) {
    int index = stageFailureIndex(path, stage, status);
    stageFailureCounts[index] = increment(stageFailureCounts[index]);
  }

  private static int statusIndex(IndexedGroupFailureStage stage, StatusCode status) {
    return stage.ordinal() * STATUS_COUNT + status.ordinal();
  }

  private static int stageFailureIndex(
      IndexedCommitPath path, IndexedCommitStage stage, StatusCode status) {
    return (path.ordinal() * STAGE_COUNT + stage.ordinal()) * STATUS_COUNT + status.ordinal();
  }

  private static int latencyBucket(long elapsedNanos) {
    if (elapsedNanos <= 1) return 0;
    return Math.min(LATENCY_BUCKETS - 1, 63 - Long.numberOfLeadingZeros(elapsedNanos));
  }

  private long increment(long value) {
    if (value == Long.MAX_VALUE) {
      overflowed = true;
      return value;
    }
    return value + 1;
  }

  private long add(long current, long value) {
    if (value < 0 || Long.MAX_VALUE - current < value) {
      overflowed = true;
      return Long.MAX_VALUE;
    }
    return current + value;
  }

  private static long sum(long left, long right) {
    return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
  }

  private boolean validSuccessfulGroupPopulation() {
    if (successfulGroupCohorts == 0) {
      return successfulGroupTransactions == 0 && maximumSuccessfulCohort == 0;
    }
    if (maximumSuccessfulCohort <= 0
        || successfulGroupTransactions < successfulGroupCohorts) return false;
    long maximumTransactions = successfulGroupCohorts > Long.MAX_VALUE / maximumSuccessfulCohort
        ? Long.MAX_VALUE : successfulGroupCohorts * maximumSuccessfulCohort;
    int maximumBucket = 31 - Integer.numberOfLeadingZeros(maximumSuccessfulCohort);
    return successfulGroupTransactions <= maximumTransactions
        && successfulCohortSizeCounts[maximumBucket] != 0;
  }

  private static void fill(long[][] values) {
    for (long[] row : values) java.util.Arrays.fill(row, 0);
  }

  private static void copy(long[][] target, long[][] source) {
    for (int index = 0; index < target.length; index++) {
      System.arraycopy(source[index], 0, target[index], 0, target[index].length);
    }
  }

  private static void copy(long[] target, long[] source) {
    System.arraycopy(source, 0, target, 0, target.length);
  }
}
