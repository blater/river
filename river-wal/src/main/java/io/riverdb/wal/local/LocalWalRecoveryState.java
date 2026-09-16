package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalCommitGroupCodec;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Owns reusable recovery readers and recovery-only group validation state. */
final class LocalWalRecoveryState {
  private final LocalWal owner;
  private final LocalWalAppendState append;
  private final WalRecordHeader header = new WalRecordHeader();
  private final io.riverdb.format.wal.WalCommitGroupHeader footer =
      new io.riverdb.format.wal.WalCommitGroupHeader();
  private final LocalWalReadResult suffixResult = new LocalWalReadResult();
  private final io.riverdb.platform.file.FileSizeResult fileSize =
      new io.riverdb.platform.file.FileSizeResult();
  private long commitSequence;
  private long maximumTransactionId;

  LocalWalRecoveryState(LocalWal owner, LocalWalAppendState append) {
    this.owner = owner;
    this.append = append;
  }

  WalRecordHeader header() { return header; }
  io.riverdb.format.wal.WalCommitGroupHeader footer() { return footer; }
  ByteBuffer recordBuffer() { return append.readRecord(); }
  ByteBuffer payloadBuffer() { return append.readPayload(); }
  CRC32C checksum() { return append.checksum(); }

  StatusCode readFileSize() { return append.file().size(fileSize); }
  long fileSizeBytes() { return fileSize.sizeBytes(); }

  StatusCode readExact(long offset, ByteBuffer target) {
    int expected = target.remaining();
    StatusCode status = append.file().read(offset, target, append.ioResult());
    return status.isOk() && append.ioResult().bytesTransferred() != expected
        ? StatusCode.IO_FAILURE : status;
  }

  StatusCode readFooterAt(long offset, long limit) {
    if (offset < WalFileHeaderCodec.HEADER_BYTES
        || limit < WalCommitGroupCodec.FOOTER_BYTES
        || offset > limit - WalCommitGroupCodec.FOOTER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    ByteBuffer encoded = append.commitGroup().footer();
    encoded.clear();
    encoded.limit(WalCommitGroupCodec.FOOTER_BYTES);
    StatusCode status = readExact(offset, encoded);
    if (!status.isOk()) return status;
    encoded.flip();
    if (!WalCommitGroupCodec.matchesMagic(encoded)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode decoded = WalCommitGroupCodec.decodeHeader(encoded, footer, append.checksum());
    if (decoded.isOk()) encoded.position(0);
    return decoded;
  }

  int footerDigest() { return WalCommitGroupCodec.chainDigest(append.commitGroup().footer()); }
  int groupDigest() { return append.groupDigest(); }

  void beginGroup() {
    append.commitGroup().begin(0);
    commitSequence = append.lastAppendedCommitSequence();
    maximumTransactionId = append.maximumTransactionId();
  }

  void includeRecord(ByteBuffer record, int recordBytes) {
    append.commitGroup().includeRaw(record, recordBytes);
  }

  boolean validFooter(long groupStart, long recordBytes, long recordCount,
      long firstSequence, long lastSequence) {
    return validFooterRecords(groupStart, recordBytes, recordCount, firstSequence, lastSequence)
        && footer.previousDigest() == append.groupDigest();
  }

  boolean validFooterRecords(long groupStart, long recordBytes, long recordCount,
      long firstSequence, long lastSequence) {
    return footer.groupStart() == groupStart
        && footer.recordBytes() == recordBytes
        && footer.recordCount() == recordCount
        && footer.firstJournalSequence() == firstSequence
        && footer.lastJournalSequence() == lastSequence
        && footer.groupChecksum() == append.commitGroup().checksumValue();
  }

  void commitGroup() {
    append.commitRecoveredGroup(commitSequence, maximumTransactionId);
  }

  StatusCode truncateTail(long validEnd, long sequence) {
    StatusCode status = append.file().truncate(validEnd);
    if (status.isOk()) {
      status = owner.forceState().forceFile(LocalWalForceCause.RECOVERY_MAINTENANCE, validEnd);
    }
    if (status.isOk()) {
      append.setRecoveryFrontier(validEnd, sequence);
      append.clearSealed();
    }
    return status;
  }

  StatusCode truncateDecisionless(long startOffset, long firstJournalSequence) {
    StatusCode admission = owner.admissionStatus();
    if (!admission.isOk()) return admission;
    if (!owner.recoveryTailOpen || owner.hasDurableQuorum() || owner.hasActiveReservation()
        || owner.hasPendingRecords() || owner.hasRetainedForceTarget()
        || owner.hasOpenLogicalStream()
        || startOffset < WalFileHeaderCodec.HEADER_BYTES || startOffset >= append.tailEnd()
        || firstJournalSequence <= 0 || firstJournalSequence >= append.nextJournalSequence()) {
      return StatusCode.CONFLICT;
    }
    int predecessorDigest;
    if (startOffset == WalFileHeaderCodec.HEADER_BYTES) {
      predecessorDigest = 0;
    } else {
      long footerOffset = startOffset - WalCommitGroupCodec.FOOTER_BYTES;
      if (footerOffset < WalFileHeaderCodec.HEADER_BYTES) return StatusCode.CONFLICT;
      StatusCode status = readFooterAt(footerOffset, startOffset);
      if (!status.isOk()) {
        return status == StatusCode.INVALID_EXTERNAL_INPUT ? StatusCode.CONFLICT : status;
      }
      if (footer.groupStart() > footerOffset
          || footer.groupBytes() != startOffset - footer.groupStart()) {
        return StatusCode.CONFLICT;
      }
      predecessorDigest = footerDigest();
    }
    long offset = startOffset;
    long sequence = firstJournalSequence;
    while (offset < append.tailEnd()) {
      StatusCode status = LocalWalReader.read(owner, offset, suffixResult);
      if (!status.isOk()) return status;
      WalRecordHeader value = suffixResult.header();
      if (value.journalSequence() != sequence || value.decisionCode() != 0
          || value.commitSequence() != 0 || suffixResult.nextOffset() <= offset) {
        return StatusCode.CORRUPTION;
      }
      offset = suffixResult.nextOffset();
      sequence = sequence == Long.MAX_VALUE ? 0 : sequence + 1;
    }
    if (offset != append.tailEnd() || sequence != append.nextJournalSequence()) {
      return StatusCode.CORRUPTION;
    }
    long maximum = 1;
    offset = WalFileHeaderCodec.HEADER_BYTES;
    while (offset < startOffset) {
      StatusCode status = LocalWalReader.read(owner, offset, suffixResult);
      if (!status.isOk()) return status;
      if (suffixResult.nextOffset() <= offset || suffixResult.nextOffset() > startOffset) {
        return StatusCode.CORRUPTION;
      }
      maximum = Math.max(maximum, suffixResult.header().transactionId());
      offset = suffixResult.nextOffset();
    }
    StatusCode status = truncateTail(startOffset, firstJournalSequence);
    if (status.isOk()) {
      append.setDigest(predecessorDigest);
      append.setMaximumTransactionId(maximum);
      owner.recoveryTailOpen = false;
    } else {
      owner.markFailed();
    }
    return status;
  }

  StatusCode completeRecovery() {
    StatusCode status = owner.admissionStatus();
    if (!status.isOk()) return status;
    if (owner.hasActiveReservation() || owner.hasPendingRecords()
        || owner.hasRetainedForceTarget() || owner.hasOpenLogicalStream()) {
      return StatusCode.CONFLICT;
    }
    owner.recoveryTailOpen = false;
    return StatusCode.OK;
  }

  boolean validDecision(WalRecordHeader value) {
    return owner.decisionCodeValid(
        value.transactionId(), value.commitSequence(), value.decisionCode(), commitSequence);
  }

  void acceptRecord(WalRecordHeader value) {
    if (value.decisionCode() == 1) commitSequence = value.commitSequence();
    maximumTransactionId = Math.max(maximumTransactionId, value.transactionId());
  }

  void finish(long offset, long sequence) { append.setRecoveryFrontier(offset, sequence); }
}
