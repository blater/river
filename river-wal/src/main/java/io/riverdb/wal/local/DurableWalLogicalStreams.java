package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;

/** Owns follower capabilities and reusable storage for streamed quorum batches. */
final class DurableWalLogicalStreams {
  private final LocalWalLogicalStream[] streams =
      new LocalWalLogicalStream[DurableWalQuorum.MAXIMUM_FOLLOWERS];
  private final LocalWalGroupReservation[] reservations =
      new LocalWalGroupReservation[DurableWalQuorum.MAXIMUM_FOLLOWERS];
  private final LocalWalGroupAppendResult[] appendResults =
      new LocalWalGroupAppendResult[DurableWalQuorum.MAXIMUM_FOLLOWERS];
  private final LocalWalForceResult[] forceResults =
      new LocalWalForceResult[DurableWalQuorum.MAXIMUM_FOLLOWERS];
  private final int[] payloadBytes = new int[LocalWal.MAX_PENDING_RECORDS];
  private final LocalWalReadResult read = new LocalWalReadResult();
  private final DurableWalQuorum quorum;
  private boolean finalBatch;
  private long commitSequence;

  DurableWalLogicalStreams(DurableWalQuorum owner) {
    quorum = owner;
    for (int index = 0; index < owner.followerCount(); index++) {
      streams[index] = new LocalWalLogicalStream();
      reservations[index] = new LocalWalGroupReservation();
      appendResults[index] = new LocalWalGroupAppendResult();
      forceResults[index] = new LocalWalForceResult();
    }
  }

  StatusCode begin(long transactionId, int formatId, int formatVersion) {
    for (int follower = 0; follower < quorum.followerCount(); follower++) {
      if (!quorum.followerAvailable(follower)) continue;
      StatusCode status = quorum.follower(follower).beginLogicalStream(
          transactionId, formatId, formatVersion, streams[follower]);
      if (!status.isOk()) quorum.retireFollower(follower);
    }
    StatusCode status = quorum.retainQuorum();
    if (!status.isOk()) fence();
    return status;
  }

  StatusCode replicate(LocalWal primary, int recordCount) {
    if (quorum.fenced() || primary == null || recordCount <= 0) {
      return StatusCode.FENCED;
    }
    StatusCode status = inspect(primary, recordCount);
    if (status.isOk()) status = reserveFollowers(recordCount);
    if (status.isOk()) status = copyPayloads(primary, recordCount);
    return status.isOk() ? publishFollowers(primary, recordCount) : quorum.fence(status);
  }

  StatusCode cancel() {
    for (int follower = 0; follower < quorum.followerCount(); follower++) {
      if (!quorum.followerAvailable(follower) || !streams[follower].isActive()) continue;
      StatusCode status = quorum.follower(follower).cancelLogicalStream(streams[follower]);
      if (!status.isOk()) retireAndFence(follower);
    }
    return quorum.retainQuorum();
  }

  void fence() {
    for (int follower = 0; follower < quorum.followerCount(); follower++) {
      if (streams[follower].isActive()) {
        quorum.follower(follower).fenceLogicalStream(streams[follower]);
      }
    }
    quorum.fence(StatusCode.FENCED);
  }

  private StatusCode inspect(LocalWal primary, int count) {
    finalBatch = false;
    commitSequence = 0;
    for (int record = 0; record < count; record++) {
      StatusCode status = primary.readForcedRecord(record, read);
      if (!status.isOk()) return status;
      WalRecordHeader header = read.header();
      payloadBytes[record] = header.payloadBytes();
      if (header.decisionCode() != 0) {
        if (record + 1 != count || header.decisionCode() != 1) {
          return StatusCode.CORRUPTION;
        }
        finalBatch = true;
        commitSequence = header.commitSequence();
      }
    }
    return StatusCode.OK;
  }

  private StatusCode reserveFollowers(int count) {
    for (int follower = 0; follower < quorum.followerCount(); follower++) {
      if (!quorum.followerAvailable(follower)) continue;
      StatusCode status = quorum.follower(follower).reserveLogicalStreamBatch(
          streams[follower], payloadBytes, count, reservations[follower]);
      if (!status.isOk()) retireAndFence(follower);
    }
    return quorum.retainQuorum();
  }

  private StatusCode copyPayloads(LocalWal primary, int count) {
    for (int record = 0; record < count; record++) {
      StatusCode status = primary.readForcedRecord(record, read);
      if (!status.isOk()) return status;
      ByteBuffer source = read.payload();
      for (int follower = 0; follower < quorum.followerCount(); follower++) {
        if (!quorum.followerAvailable(follower)) continue;
        source.position(0);
        reservations[follower].writablePayload(record).put(source);
      }
    }
    return StatusCode.OK;
  }

  private StatusCode publishFollowers(LocalWal primary, int count) {
    int durableNodes = 1;
    long batchPayload = 0;
    for (int record = 0; record < count; record++) batchPayload += payloadBytes[record];
    for (int follower = 0; follower < quorum.followerCount(); follower++) {
      if (!quorum.followerAvailable(follower)) continue;
      StatusCode status = publishFollower(follower);
      if (!status.isOk()) retireAndFence(follower);
      else {
        durableNodes++;
        quorum.addReplicatedPayloadBytes(batchPayload);
      }
    }
    return quorum.acceptLogicalDurability(durableNodes, primary.currentCommitSequence());
  }

  private StatusCode publishFollower(int follower) {
    LocalWal target = quorum.follower(follower);
    StatusCode status = finalBatch
        ? target.appendLogicalStreamFinal(
            streams[follower], reservations[follower], commitSequence,
            appendResults[follower])
        : target.appendLogicalStreamContinuation(
            streams[follower], reservations[follower], appendResults[follower]);
    if (status.isOk()) {
      status = target.forceLogicalStreamBatch(streams[follower], forceResults[follower]);
    }
    return status.isOk() ? target.releaseLogicalStreamBatch(streams[follower]) : status;
  }

  private void retireAndFence(int follower) {
    if (streams[follower].isActive()) {
      quorum.follower(follower).fenceLogicalStream(streams[follower]);
    }
    quorum.retireFollower(follower);
  }
}
