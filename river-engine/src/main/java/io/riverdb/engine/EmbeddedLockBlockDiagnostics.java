package io.riverdb.engine;

import io.riverdb.tx.LockBlockCausalitySnapshot;

/** Cold text serialization for one reconciled generic lock-block capture. */
final class EmbeddedLockBlockDiagnostics {
  private EmbeddedLockBlockDiagnostics() { }

  static void append(
      StringBuilder target, LockBlockCausalitySnapshot snapshot, int retainedSnapshots) {
    target.append("server_capture_retained_snapshots=").append(retainedSnapshots).append('\n')
        .append("server_capture_lock_block_format_version=")
        .append(snapshot.formatVersion()).append('\n')
        .append("server_capture_lock_block_valid=").append(snapshot.reconciles()).append('\n')
        .append("server_capture_lock_block_bucket_capacity=")
        .append(snapshot.bucketCapacity()).append('\n')
        .append("server_capture_lock_block_bucket_total=")
        .append(snapshot.bucketTotal()).append('\n')
        .append("server_capture_lock_block_unclassified=")
        .append(snapshot.unclassifiedBlocks()).append('\n')
        .append("server_capture_lock_block_overflows=").append(snapshot.overflows()).append('\n')
        .append("server_capture_lock_block_consumed=").append(snapshot.consumed()).append('\n')
        .append("server_capture_lock_block_failed=").append(snapshot.failed()).append('\n')
        .append("server_capture_lock_block_revoked_after_handoff=")
        .append(snapshot.revokedAfterHandoff()).append('\n')
        .append("server_capture_lock_block_victim_selections=")
        .append(snapshot.victimSelections()).append('\n')
        .append("server_capture_lock_blocked_consumed=")
        .append(snapshot.blockedConsumed()).append('\n')
        .append("server_capture_lock_blocked_timed_out=")
        .append(snapshot.blockedTimedOut()).append('\n')
        .append("server_capture_lock_blocked_cancelled=")
        .append(snapshot.blockedCancelled()).append('\n')
        .append("server_capture_lock_blocked_deadlocked=")
        .append(snapshot.blockedDeadlocked()).append('\n')
        .append("server_capture_lock_blocked_failed=")
        .append(snapshot.blockedFailed()).append('\n');
    int emitted = 0;
    for (int index = 0; index < snapshot.bucketCapacity(); index++) {
      long count = snapshot.bucketCountAt(index);
      if (count == 0) continue;
      target.append("server_capture_lock_block_bucket_").append(emitted)
          .append("_source_index=").append(index).append('\n')
          .append("server_capture_lock_block_bucket_").append(emitted)
          .append("_scope=").append(snapshot.scopeAt(index)).append('\n')
          .append("server_capture_lock_block_bucket_").append(emitted)
          .append("_requested_mode=").append(snapshot.requestedModeAt(index)).append('\n')
          .append("server_capture_lock_block_bucket_").append(emitted)
          .append("_blocker_mode=").append(snapshot.blockerModeAt(index)).append('\n')
          .append("server_capture_lock_block_bucket_").append(emitted)
          .append("_waiter_queue=").append(snapshot.waiterQueueKindAt(index)).append('\n')
          .append("server_capture_lock_block_bucket_").append(emitted)
          .append("_blocker_queue=").append(snapshot.blockerQueueKindAt(index)).append('\n')
          .append("server_capture_lock_block_bucket_").append(emitted)
          .append("_relationship=").append(snapshot.queueRelationshipAt(index)).append('\n')
          .append("server_capture_lock_block_bucket_").append(emitted)
          .append("_grant_precondition=").append(snapshot.grantPreconditionAt(index)).append('\n')
          .append("server_capture_lock_block_bucket_").append(emitted)
          .append("_count=").append(count).append('\n');
      emitted++;
    }
    target.append("server_capture_lock_block_bucket_count=").append(emitted).append('\n');
  }
}
