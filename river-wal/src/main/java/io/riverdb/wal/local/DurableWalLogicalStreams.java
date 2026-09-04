package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;

/** Replicates logical-stream records sequentially without retaining a record-count-sized batch. */
final class DurableWalLogicalStreams {
  private final LocalWalLogicalStream[] streams =
      new LocalWalLogicalStream[DurableWalQuorum.MAXIMUM_FOLLOWERS];
  private final LocalWalGroupAppendResult[] appendResults =
      new LocalWalGroupAppendResult[DurableWalQuorum.MAXIMUM_FOLLOWERS];
  private final LocalWalForceResult[] forceResults =
      new LocalWalForceResult[DurableWalQuorum.MAXIMUM_FOLLOWERS];
  private final LocalWalReadResult read = new LocalWalReadResult();
  private final LocalWalForcedCursor cursor = new LocalWalForcedCursor();
  private final CopiedRecord copied = new CopiedRecord();
  private final DurableWalQuorum quorum;
  private long transactionId;
  private int formatId;
  private int formatVersion;

  DurableWalLogicalStreams(DurableWalQuorum owner) {
    quorum = owner;
    for (int index = 0; index < owner.followerCount(); index++) {
      streams[index] = new LocalWalLogicalStream();
      appendResults[index] = new LocalWalGroupAppendResult();
      forceResults[index] = new LocalWalForceResult();
    }
  }

  StatusCode begin(long ownerTransactionId, int ownerFormatId, int ownerFormatVersion) {
    transactionId = ownerTransactionId;
    formatId = ownerFormatId;
    formatVersion = ownerFormatVersion;
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

  StatusCode replicate(
      LocalWal primary, long recordCount, LocalWalForceCause cause) {
    if (quorum.fenced() || primary == null || recordCount <= 0) {
      return StatusCode.FENCED;
    }
    StatusCode status = primary.openForcedCursor(cursor);
    long payloadBytes = 0;
    boolean finalBatch = false;
    for (long record = 0; status.isOk() && record < recordCount; record++) {
      status = cursor.next(read);
      if (!status.isOk()) break;
      WalRecordHeader header = read.header();
      boolean decision = header.decisionCode() != 0;
      if (header.transactionId() != transactionId
          || header.formatId() != formatId
          || header.formatVersion() != formatVersion
          || decision && (record + 1 != recordCount || header.decisionCode() != 1)
          || !decision && header.commitSequence() != 0) {
        status = StatusCode.CORRUPTION;
        break;
      }
      copied.set(read.payload(), header.payloadBytes());
      payloadBytes = add(payloadBytes, header.payloadBytes());
      if (payloadBytes < 0) {
        status = StatusCode.RESOURCE_EXHAUSTED;
        break;
      }
      finalBatch = decision;
      for (int follower = 0; follower < quorum.followerCount(); follower++) {
        if (!quorum.followerAvailable(follower)) continue;
        LocalWal target = quorum.follower(follower);
        StatusCode appended = decision
            ? target.appendLogicalStreamFinal(
                streams[follower], copied, header.commitSequence(), appendResults[follower])
            : target.appendLogicalStreamContinuation(
                streams[follower], copied, appendResults[follower]);
        if (!appended.isOk()) retireAndFence(follower);
      }
      status = quorum.retainQuorum();
    }
    cursor.reset();
    if (!status.isOk()) return quorum.fence(status);
    return forceFollowers(primary, cause, payloadBytes, finalBatch);
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

  private StatusCode forceFollowers(
      LocalWal primary,
      LocalWalForceCause cause,
      long payloadBytes,
      boolean finalBatch) {
    int durableNodes = 1;
    for (int follower = 0; follower < quorum.followerCount(); follower++) {
      if (!quorum.followerAvailable(follower)) continue;
      LocalWal target = quorum.follower(follower);
      StatusCode status = target.forceLogicalStreamBatch(
          streams[follower], forceResults[follower], cause);
      if (status.isOk()) status = target.releaseLogicalStreamBatch(streams[follower]);
      if (!status.isOk()) {
        retireAndFence(follower);
      } else {
        durableNodes++;
        quorum.addReplicatedPayloadBytes(payloadBytes);
      }
    }
    if (!finalBatch) {
      for (int follower = 0; follower < quorum.followerCount(); follower++) {
        if (quorum.followerAvailable(follower) && !streams[follower].isActive()) {
          return quorum.fence(StatusCode.INVARIANT_BROKEN);
        }
      }
    }
    return quorum.acceptLogicalDurability(durableNodes, primary.currentCommitSequence());
  }

  private void retireAndFence(int follower) {
    if (streams[follower].isActive()) {
      quorum.follower(follower).fenceLogicalStream(streams[follower]);
    }
    quorum.retireFollower(follower);
  }

  private static long add(long current, int value) {
    return value < 0 || current > Long.MAX_VALUE - value ? -1 : current + value;
  }

  private static final class CopiedRecord implements LocalWalRecordBatch {
    private ByteBuffer source;
    private int bytes;

    void set(ByteBuffer payload, int payloadBytes) {
      source = payload;
      bytes = payloadBytes;
    }

    @Override
    public int recordCount() { return 1; }

    @Override
    public int payloadBytes(int record) { return record == 0 ? bytes : -1; }

    @Override
    public StatusCode encodePayload(int record, ByteBuffer target) {
      if (record != 0 || source == null || target == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      source.position(0);
      target.put(source);
      return StatusCode.OK;
    }
  }
}
