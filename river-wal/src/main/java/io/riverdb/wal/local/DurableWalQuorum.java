package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;

/**
 * Fixed-membership synchronous replication of one primary WAL force batch.
 *
 * <p>The primary is one durable node. Each enabled follower receives the exact logical records
 * into its own provider-owned WAL storage and forces them before the primary reports quorum
 * durability. A follower failure removes that follower from subsequent batches. Loss of quorum
 * fences the primary because its local force may already contain an outcome whose quorum status
 * cannot be safely guessed. This is fixed-membership durable replication, not leader election or
 * a claim of a complete consensus protocol.
 */
final class DurableWalQuorum {
  static final int MAXIMUM_FOLLOWERS = 6;

  private final LocalWal[] followers = new LocalWal[MAXIMUM_FOLLOWERS];
  private final boolean[] available = new boolean[MAXIMUM_FOLLOWERS];
  private final LocalWalReservation[] reservations =
      new LocalWalReservation[MAXIMUM_FOLLOWERS];
  private final LocalWalAppendResult[] appendResults =
      new LocalWalAppendResult[MAXIMUM_FOLLOWERS];
  private final LocalWalForceResult[] forceResults =
      new LocalWalForceResult[MAXIMUM_FOLLOWERS];
  private final LocalWalReadResult primaryRead = new LocalWalReadResult();
  private final int followerCount;
  private final int requiredNodeCount;
  private long replicatedPayloadBytes;
  private long quorumDurableCommitSequence;
  private int availableNodeCount;
  private boolean fenced;

  DurableWalQuorum(LocalWal[] configuredFollowers, int requiredNodes) {
    followerCount = configuredFollowers.length;
    requiredNodeCount = requiredNodes;
    availableNodeCount = followerCount + 1;
    for (int index = 0; index < followerCount; index++) {
      followers[index] = configuredFollowers[index];
      available[index] = true;
      reservations[index] = new LocalWalReservation();
      appendResults[index] = new LocalWalAppendResult();
      forceResults[index] = new LocalWalForceResult();
    }
  }

  StatusCode replicateForcedBatch(LocalWal primary, int recordCount) {
    if (fenced || primary == null || recordCount <= 0) {
      return StatusCode.FENCED;
    }
    for (int record = 0; record < recordCount; record++) {
      StatusCode read = primary.readForcedRecord(record, primaryRead);
      if (!read.isOk()) {
        fenced = true;
        return read;
      }
      WalRecordHeader header = primaryRead.header();
      ByteBuffer source = primaryRead.payload();
      for (int follower = 0; follower < followerCount; follower++) {
        if (!available[follower]) {
          continue;
        }
        LocalWal target = followers[follower];
        LocalWalReservation reservation = reservations[follower];
        StatusCode status = target.reserve(header.payloadBytes(), reservation);
        if (status.isOk()) {
          source.position(0);
          reservation.writablePayload().put(source);
          status = target.appendUnforced(
              reservation,
              header.transactionId(),
              header.commitSequence(),
              header.decisionCode(),
              header.formatId(),
              header.formatVersion(),
              appendResults[follower]);
        }
        if (!status.isOk()) {
          retireFollower(follower);
        } else {
          replicatedPayloadBytes += header.payloadBytes();
        }
      }
    }
    int durableNodes = 1;
    for (int follower = 0; follower < followerCount; follower++) {
      if (!available[follower]) {
        continue;
      }
      StatusCode status = followers[follower].forcePending(forceResults[follower]);
      if (status.isOk()) {
        status = followers[follower].releaseForcedBatch();
      }
      if (!status.isOk()) {
        retireFollower(follower);
      } else {
        durableNodes++;
      }
    }
    availableNodeCount = durableNodes;
    if (durableNodes < requiredNodeCount) {
      fenced = true;
      return StatusCode.FENCED;
    }
    quorumDurableCommitSequence = primary.currentCommitSequence();
    return StatusCode.OK;
  }

  int requiredNodeCount() {
    return requiredNodeCount;
  }

  int availableNodeCount() {
    return availableNodeCount;
  }

  long replicatedPayloadBytes() {
    return replicatedPayloadBytes;
  }

  long quorumDurableCommitSequence() {
    return quorumDurableCommitSequence;
  }

  private void retireFollower(int follower) {
    if (available[follower]) {
      available[follower] = false;
      availableNodeCount--;
    }
  }
}
