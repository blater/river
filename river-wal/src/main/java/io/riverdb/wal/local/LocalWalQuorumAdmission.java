package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;

/** Validates and installs a fixed-membership durable WAL quorum. */
final class LocalWalQuorumAdmission {
  private LocalWalQuorumAdmission() {
  }

  static StatusCode enable(LocalWal primary, LocalWal[] followers, int requiredNodeCount) {
    if (followers == null
        || followers.length == 0
        || followers.length > DurableWalQuorum.MAXIMUM_FOLLOWERS
        || requiredNodeCount < 2
        || requiredNodeCount > followers.length + 1
        || primary.hasDurableQuorum()
        || primary.hasOpenLogicalStream()
        || primary.hasActiveReservation()
        || primary.hasPendingRecords()
        || primary.hasForcedBatch()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode admission = primary.admissionStatus();
    if (!admission.isOk()) {
      return admission;
    }
    for (int index = 0; index < followers.length; index++) {
      LocalWal follower = followers[index];
      if (follower == null
          || follower == primary
          || follower.hasDurableQuorum()
          || follower.hasOpenLogicalStream()
          || !primary.databaseIncarnation().equals(follower.databaseIncarnation())
          || !primary.walGeneration().equals(follower.walGeneration())
          || primary.tailEnd() != follower.tailEnd()
          || primary.durableEnd() != follower.durableEnd()
          || primary.nextJournalSequence() != follower.nextJournalSequence()
          || primary.currentCommitSequence() != follower.currentCommitSequence()) {
        return StatusCode.CONFLICT;
      }
      for (int previous = 0; previous < index; previous++) {
        if (followers[previous] == follower) {
          return StatusCode.CONFLICT;
        }
      }
      StatusCode equivalent = equivalentDurableHistory(primary, follower);
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

  private static StatusCode equivalentDurableHistory(LocalWal primary, LocalWal follower) {
    LocalWalReadResult primaryRead = new LocalWalReadResult();
    LocalWalReadResult followerRead = new LocalWalReadResult();
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    while (offset < primary.durableEnd()) {
      StatusCode status = primary.read(offset, primaryRead);
      if (status.isOk()) {
        status = follower.read(offset, followerRead);
      }
      if (!status.isOk()) {
        return status;
      }
      WalRecordHeader primaryHeader = primaryRead.header();
      WalRecordHeader followerHeader = followerRead.header();
      if (!sameHeader(primaryRead, followerRead, primaryHeader, followerHeader)) {
        return StatusCode.CORRUPTION;
      }
      ByteBuffer primaryPayload = primaryRead.payload();
      ByteBuffer followerPayload = followerRead.payload();
      for (int index = 0; index < primaryHeader.payloadBytes(); index++) {
        if (primaryPayload.get(index) != followerPayload.get(index)) {
          return StatusCode.CORRUPTION;
        }
      }
      offset = primaryRead.nextOffset();
    }
    return offset == primary.durableEnd() ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static boolean sameHeader(
      LocalWalReadResult primaryRead,
      LocalWalReadResult followerRead,
      WalRecordHeader primary,
      WalRecordHeader follower) {
    return primaryRead.nextOffset() == followerRead.nextOffset()
        && primary.totalBytes() == follower.totalBytes()
        && primary.payloadBytes() == follower.payloadBytes()
        && primary.formatId() == follower.formatId()
        && primary.formatVersion() == follower.formatVersion()
        && primary.journalSequence() == follower.journalSequence()
        && primary.transactionId() == follower.transactionId()
        && primary.commitSequence() == follower.commitSequence()
        && primary.decisionCode() == follower.decisionCode();
  }
}
