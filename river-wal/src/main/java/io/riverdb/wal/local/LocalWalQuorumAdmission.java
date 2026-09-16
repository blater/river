package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Validates and installs a fixed-membership durable WAL quorum. */
final class LocalWalQuorumAdmission {
  private LocalWalQuorumAdmission() {
  }

  static StatusCode enable(LocalWal primary, LocalWal[] followers, int requiredNodeCount) {
    StatusCode validation = validateArguments(primary, followers, requiredNodeCount);
    if (!validation.isOk()) return validation;
    StatusCode admission = primary.admissionStatus();
    if (!admission.isOk()) {
      return admission;
    }
    for (int index = 0; index < followers.length; index++) {
      LocalWal follower = followers[index];
      StatusCode member = LocalWalQuorumMembership.validate(primary, follower);
      if (!member.isOk()) return member;
      for (int previous = 0; previous < index; previous++) {
        if (followers[previous] == follower) {
          return StatusCode.CONFLICT;
        }
      }
      StatusCode equivalent = LocalWalQuorumHistory.equivalent(primary, follower);
      if (!equivalent.isOk()) {
        return equivalent;
      }
    }
    StatusCode recovery = primary.completeRecovery();
    if (!recovery.isOk()) return recovery;
    for (LocalWal follower : followers) {
      recovery = follower.completeRecovery();
      if (!recovery.isOk()) return recovery;
    }
    LocalWal[] ownedFollowers = new LocalWal[followers.length];
    System.arraycopy(followers, 0, ownedFollowers, 0, followers.length);
    primary.installDurableQuorum(new DurableWalQuorum(ownedFollowers, requiredNodeCount));
    return StatusCode.OK;
  }

  private static StatusCode validateArguments(
      LocalWal primary, LocalWal[] followers, int requiredNodeCount) {
    if (primary == null || followers == null || followers.length == 0
        || followers.length > DurableWalQuorum.MAXIMUM_FOLLOWERS
        || requiredNodeCount < 2 || requiredNodeCount > followers.length + 1
        || primary.hasDurableQuorum() || primary.hasOpenLogicalStream()
        || primary.hasActiveReservation() || primary.hasPendingRecords()
        || primary.hasRetainedForceTarget()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }
}
