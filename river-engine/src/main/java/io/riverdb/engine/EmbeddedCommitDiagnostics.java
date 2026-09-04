package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedCommitPath;
import io.riverdb.engine.table.IndexedCommitQueueTelemetry;
import io.riverdb.engine.table.IndexedCommitStage;
import io.riverdb.engine.table.IndexedDirectCommitReason;
import io.riverdb.engine.table.IndexedGroupCommitIneligibilityReason;
import io.riverdb.engine.table.IndexedGroupCommitTelemetry;
import io.riverdb.engine.table.IndexedGroupFailureStage;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.wal.local.LocalWalForceCause;
import io.riverdb.wal.local.LocalWalMetrics;

/** Formats one cold, bounded, reconciled commit and WAL telemetry snapshot. */
final class EmbeddedCommitDiagnostics {
  private EmbeddedCommitDiagnostics() { }

  static void append(
      StringBuilder target,
      IndexedGroupCommitTelemetry commits,
      LocalWalMetrics forces) {
    append(target, commits, forces, "");
  }

  static void append(
      StringBuilder target,
      IndexedGroupCommitTelemetry commits,
      LocalWalMetrics forces,
      String scope) {
    summary(target, commits, scope);
    eligibility(target, commits, scope);
    groups(target, commits, scope);
    queue(target, commits.queue(), scope);
    stages(target, commits, scope);
    wal(target, forces, scope);
  }

  private static void summary(
      StringBuilder target, IndexedGroupCommitTelemetry commits, String scope) {
    target.append("server_").append(scope).append("commit_telemetry_valid=")
        .append(commits.reconciles()).append('\n')
        .append("server_").append(scope).append("commit_submissions=")
        .append(commits.totalCommitSubmissions()).append('\n')
        .append("server_").append(scope).append("commit_write_submissions=")
        .append(commits.writeCommitSubmissions()).append('\n')
        .append("server_").append(scope).append("commit_read_only_submissions=")
        .append(commits.readOnlyCommitSubmissions()).append('\n')
        .append("server_").append(scope).append("commit_failed_before_submission=")
        .append(commits.failedBeforeSubmission()).append('\n')
        .append("server_").append(scope).append("commit_groupable_transactions=")
        .append(commits.groupableTransactions()).append('\n')
        .append("server_").append(scope).append("commit_initially_ineligible_transactions=")
        .append(commits.initiallyIneligibleTransactions()).append('\n')
        .append("server_").append(scope).append("commit_groupable_admissions=")
        .append(commits.groupableAdmissions()).append('\n')
        .append("server_").append(scope).append("commit_initially_ineligible_admissions=")
        .append(commits.initiallyIneligibleAdmissions()).append('\n')
        .append("server_").append(scope).append("commit_attempted_group_cohorts=")
        .append(commits.attemptedGroupCohorts()).append('\n')
        .append("server_").append(scope).append("commit_attempted_group_transactions=")
        .append(commits.attemptedGroupTransactions()).append('\n')
        .append("server_").append(scope).append("commit_successful_group_cohorts=")
        .append(commits.successfulGroupCohorts()).append('\n')
        .append("server_").append(scope).append("commit_successful_group_transactions=")
        .append(commits.successfulGroupTransactions()).append('\n')
        .append("server_").append(scope).append("commit_successful_group_average=");
    appendOneDecimal(
        target,
        commits.successfulGroupTransactions(),
        commits.successfulGroupCohorts());
    target.append('\n')
        .append("server_").append(scope).append("commit_successful_group_maximum=")
        .append(commits.maximumSuccessfulCohort()).append('\n')
        .append("server_").append(scope).append("commit_direct_transactions=")
        .append(commits.directCommitTransactions()).append('\n')
        .append("server_").append(scope).append("commit_group_lock_holdings_released=")
        .append(commits.groupLockHoldingsReleased()).append('\n');
    for (LockScope lockScope : LockScope.values()) {
      long released = commits.groupLockHoldingsReleased(lockScope);
      if (released != 0) target.append(scope)
          .append("commit_group_lock_holdings_released scope=")
          .append(lockScope)
          .append(" holdings=").append(released).append('\n');
    }
  }

  private static void queue(
      StringBuilder target, IndexedCommitQueueTelemetry queue, String scope) {
    target.append("server_").append(scope).append("commit_queue_telemetry_valid=")
        .append(!queue.overflowed()).append('\n')
        .append("server_").append(scope).append("commit_coalescing_waits=")
        .append(queue.coalescingWaits()).append('\n')
        .append("server_").append(scope).append("commit_coalescing_wait_nanos=")
        .append(queue.coalescingWaitNanos()).append('\n')
        .append("server_").append(scope).append("commit_queue_enqueues=")
        .append(queue.enqueues()).append('\n')
        .append("server_").append(scope).append("commit_writer_selections=")
        .append(queue.writerSelections()).append('\n')
        .append("server_").append(scope).append("commit_selected_transactions=")
        .append(queue.selectedTransactions()).append('\n')
        .append("server_").append(scope).append("commit_queue_depth_at_selection=")
        .append(queue.depthAtSelection()).append('\n')
        .append("server_").append(scope).append("commit_queue_depth_one_selections=")
        .append(queue.depthOneSelections()).append('\n')
        .append("server_").append(scope).append("commit_queue_depth_multiple_selections=")
        .append(queue.depthMultipleSelections()).append('\n')
        .append("server_").append(scope).append("commit_queue_depth_maximum=")
        .append(queue.maximumDepth()).append('\n')
        .append("server_").append(scope).append("commit_groupable_depth_at_selection=")
        .append(queue.groupableDepthAtSelection()).append('\n')
        .append("server_").append(scope).append("commit_groupable_depth_one_selections=")
        .append(queue.groupableDepthOneSelections()).append('\n')
        .append("server_").append(scope).append("commit_groupable_depth_multiple_selections=")
        .append(queue.groupableDepthMultipleSelections()).append('\n')
        .append("server_").append(scope).append("commit_groupable_depth_maximum=")
        .append(queue.maximumGroupableDepth()).append('\n')
        .append("server_").append(scope).append("commit_capacity_constrained_selections=")
        .append(queue.capacityConstrainedSelections()).append('\n')
        .append("server_").append(scope).append("commit_queue_nonempty_nanos=")
        .append(queue.nonemptyNanos()).append('\n')
        .append("server_").append(scope).append("commit_writer_busy_nanos=")
        .append(queue.writerBusyNanos()).append('\n');
  }

  private static void eligibility(
      StringBuilder target, IndexedGroupCommitTelemetry commits, String scope) {
    for (StatusCode status : StatusCode.values()) {
      long count = commits.failedBeforeStatusCount(status);
      if (count != 0) target.append(scope).append("commit_failed_before status=")
          .append(status)
          .append(" transactions=").append(count).append('\n');
    }
    for (int mask = 1; mask < IndexedGroupCommitTelemetry.PREDICATE_MASKS; mask++) {
      long count = commits.predicateMaskCount(mask);
      if (count != 0) target.append(scope).append("commit_ineligibility_mask mask=")
          .append("0x").append(Integer.toHexString(mask))
          .append(" transactions=").append(count).append('\n');
    }
    for (IndexedGroupCommitIneligibilityReason reason
        : IndexedGroupCommitIneligibilityReason.values()) {
      long count = commits.primaryIneligibilityCount(reason);
      if (count != 0) target.append(scope).append("commit_ineligible reason=").append(reason)
          .append(" transactions=").append(count).append('\n');
    }
    for (IndexedDirectCommitReason reason : IndexedDirectCommitReason.values()) {
      long count = commits.directCommitCount(reason);
      if (count != 0) target.append(scope).append("commit_direct reason=").append(reason)
          .append(" transactions=").append(count).append('\n');
    }
  }

  private static void groups(
      StringBuilder target, IndexedGroupCommitTelemetry commits, String scope) {
    for (int bucket = 0;
        bucket < IndexedGroupCommitTelemetry.COHORT_SIZE_BUCKETS;
        bucket++) {
      long count = commits.successfulCohortSizeBucket(bucket);
      if (count != 0) target.append(scope)
          .append("commit_successful_cohort_size size_upper_bound=")
          .append(IndexedGroupCommitTelemetry.cohortSizeUpperBound(bucket))
          .append(" cohorts=").append(count).append('\n');
    }
    for (IndexedGroupFailureStage stage : IndexedGroupFailureStage.values()) {
      for (StatusCode failure : StatusCode.values()) {
        long cohorts = commits.groupFailureCount(stage, failure);
        if (cohorts != 0) target.append(scope).append("commit_group_failure stage=")
            .append(stage)
            .append(" status=").append(failure)
            .append(" cohorts=").append(cohorts)
            .append(" transactions=")
            .append(commits.groupFailureTransactionStatusCount(stage, failure))
            .append('\n');
      }
    }
  }

  private static void stages(
      StringBuilder target, IndexedGroupCommitTelemetry commits, String scope) {
    for (IndexedCommitPath path : IndexedCommitPath.values()) {
      for (IndexedCommitStage stage : IndexedCommitStage.values()) {
        long count = commits.stageCount(path, stage);
        if (count != 0) target.append(scope).append("commit_stage path=").append(path)
            .append(" stage=").append(stage)
            .append(" count=").append(count)
            .append(" nanos=").append(commits.stageNanos(path, stage)).append('\n');
        for (int bucket = 0; bucket < IndexedGroupCommitTelemetry.LATENCY_BUCKETS; bucket++) {
          long samples = commits.stageLatencyBucket(path, stage, bucket);
          if (samples != 0) target.append(scope).append("commit_stage_latency path=")
              .append(path)
              .append(" stage=").append(stage)
              .append(" nanos_upper_bound=").append(upperBound(bucket))
              .append(" count=").append(samples).append('\n');
        }
        for (StatusCode failure : StatusCode.values()) {
          long failures = commits.stageFailureCount(path, stage, failure);
          if (failures != 0) target.append(scope).append("commit_stage_failure path=")
              .append(path)
              .append(" stage=").append(stage)
              .append(" status=").append(failure)
              .append(" count=").append(failures).append('\n');
        }
      }
    }
  }

  private static void wal(StringBuilder target, LocalWalMetrics forces, String scope) {
    target.append("server_").append(scope).append("wal_force_telemetry_valid=")
        .append(forces.reconciles()).append('\n')
        .append("server_").append(scope).append("wal_force_count=")
        .append(forces.totalForceCount()).append('\n')
        .append("server_").append(scope).append("wal_force_bytes=")
        .append(forces.totalForceBytes()).append('\n')
        .append("server_").append(scope).append("wal_force_nanos=")
        .append(forces.totalForceNanos()).append('\n');
    for (LocalWalForceCause cause : LocalWalForceCause.values()) {
      long count = forces.forceCount(cause);
      if (count != 0) target.append(scope).append("wal_force cause=").append(cause)
          .append(" count=").append(count)
          .append(" bytes=").append(forces.forceBytes(cause))
          .append(" nanos=").append(forces.forceNanos(cause)).append('\n');
      for (int bucket = 0; bucket < LocalWalMetrics.LATENCY_BUCKETS; bucket++) {
        long samples = forces.forceLatencyBucket(cause, bucket);
        if (samples != 0) target.append(scope).append("wal_force_latency cause=")
            .append(cause)
            .append(" nanos_upper_bound=").append(upperBound(bucket))
            .append(" count=").append(samples).append('\n');
      }
      for (StatusCode status : StatusCode.values()) {
        long outcomes = forces.forceStatusCount(cause, status);
        if (outcomes != 0) target.append(scope).append("wal_force_status cause=")
            .append(cause)
            .append(" status=").append(status)
            .append(" count=").append(outcomes).append('\n');
      }
    }
  }

  private static long upperBound(int bucket) {
    return bucket >= 62 ? Long.MAX_VALUE : (1L << (bucket + 1)) - 1;
  }

  private static void appendOneDecimal(
      StringBuilder target, long numerator, long denominator) {
    if (denominator == 0) {
      target.append("0.0");
      return;
    }
    long tenths = Math.round(10.0d * numerator / denominator);
    target.append(tenths / 10).append('.').append(tenths % 10);
  }
}
