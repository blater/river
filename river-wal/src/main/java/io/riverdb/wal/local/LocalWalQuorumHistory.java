package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;

/** Compares the durable record history before quorum membership is installed. */
final class LocalWalQuorumHistory {
  private LocalWalQuorumHistory() {}

  static StatusCode equivalent(LocalWal primary, LocalWal follower) {
    LocalWalReadResult primaryRead = new LocalWalReadResult();
    LocalWalReadResult followerRead = new LocalWalReadResult();
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    while (offset < primary.durableEnd()) {
      StatusCode status = primary.read(offset, primaryRead);
      if (status.isOk()) status = follower.read(offset, followerRead);
      if (!status.isOk()) return status;
      WalRecordHeader primaryHeader = primaryRead.header();
      WalRecordHeader followerHeader = followerRead.header();
      if (!sameHeader(primaryRead, followerRead, primaryHeader, followerHeader)) {
        return StatusCode.CORRUPTION;
      }
      ByteBuffer primaryPayload = primaryRead.payload();
      ByteBuffer followerPayload = followerRead.payload();
      for (int index = 0; index < primaryHeader.payloadBytes(); index++) {
        if (primaryPayload.get(index) != followerPayload.get(index)) return StatusCode.CORRUPTION;
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
