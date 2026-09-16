package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalCommitGroupCodec;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Owns reusable append storage and the physical WAL frontier. */
final class LocalWalAppendState {
  private final LocalWal owner;
  private DurableFile file;
  private final ByteBuffer appendRecord;
  private final ByteBuffer appendPayload;
  private final ByteBuffer readRecord;
  private final ByteBuffer readPayload;
  private final LocalWalCommitGroup commitGroup = new LocalWalCommitGroup();
  private final IoResult ioResult = new IoResult();
  private final LocalWalBatchAdmissionResult batchAdmissionResult =
      new LocalWalBatchAdmissionResult();
  private final CRC32C checksum = new CRC32C();
  private long tailEnd = io.riverdb.format.wal.WalFileHeaderCodec.HEADER_BYTES;
  private long durableEnd = io.riverdb.format.wal.WalFileHeaderCodec.HEADER_BYTES;
  private long nextJournalSequence = 1;
  private long lastCommitSequence;
  private long lastAppendedCommitSequence;
  private long pendingStart;
  private long pendingRecordCount;
  private long sealedStart;
  private long sealedRecordCount;
  private long sealedCommitSequence;
  private int sealedPreviousDigest;
  private int sealedDigest;
  private int durableDigest;
  private long maximumTransactionId = 1;
  private long copiedPayloadBytes;

  LocalWalAppendState(LocalWal owner, DurableFile file) {
    this.owner = owner;
    this.file = file;
    int capacity = WalRecordCodec.HEADER_BYTES + WalRecordCodec.MAX_PAYLOAD_BYTES;
    appendRecord = ByteBuffer.allocateDirect(capacity);
    appendPayload = payloadView(appendRecord);
    readRecord = ByteBuffer.allocateDirect(capacity);
    readPayload = payloadView(readRecord);
  }

  long tailEnd() { return tailEnd; }
  long durableEnd() { return durableEnd; }
  long nextJournalSequence() { return nextJournalSequence; }
  long nextCommitSequence() {
    return lastAppendedCommitSequence == Long.MAX_VALUE ? 0 : lastAppendedCommitSequence + 1;
  }
  long currentCommitSequence() { return lastCommitSequence; }
  long maximumTransactionId() { return maximumTransactionId; }
  long copiedPayloadBytes() { return copiedPayloadBytes; }
  DurableFile file() { return file; }
  LocalWalCommitGroup commitGroup() { return commitGroup; }
  ByteBuffer appendRecord() { return appendRecord; }
  ByteBuffer appendPayload() { return appendPayload; }
  ByteBuffer readRecord() { return readRecord; }
  ByteBuffer readPayload() { return readPayload; }
  IoResult ioResult() { return ioResult; }
  LocalWalBatchAdmissionResult batchAdmissionResult() { return batchAdmissionResult; }
  CRC32C checksum() { return checksum; }
  long pendingRecordCount() { return pendingRecordCount; }
  boolean hasPendingRecords() { return pendingRecordCount != 0 || sealedRecordCount != 0; }
  boolean hasSealedRecords() { return sealedRecordCount != 0; }
  int groupDigest() { return commitGroup.digest(); }
  int footerDigest() { return WalCommitGroupCodec.chainDigest(commitGroup.footer()); }


  void adoptCheckpointState(long commitSequence, long transactionId) {
    if (lastCommitSequence == 0) {
      lastCommitSequence = commitSequence;
      lastAppendedCommitSequence = commitSequence;
    }
    maximumTransactionId = Math.max(maximumTransactionId, transactionId);
  }

  StatusCode validateAndAdoptCheckpoint(long commitSequence, long transactionId) {
    if (commitSequence <= 0 || transactionId <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (lastCommitSequence != 0 && lastCommitSequence <= commitSequence) {
      return StatusCode.CORRUPTION;
    }
    adoptCheckpointState(commitSequence, transactionId);
    return StatusCode.OK;
  }

  void prepareAppendPayload(int payloadBytes) {
    appendRecord.clear();
    appendPayload.clear();
    appendPayload.limit(payloadBytes);
  }

  void beginPendingGroup(long firstSequence) {
    if (pendingRecordCount == 0) commitGroup.begin(firstSequence);
  }

  void includePendingRecord(ByteBuffer record, int recordBytes, long sequence) {
    commitGroup.include(record, recordBytes, sequence);
  }

  StatusCode writeAppendRecord(long offset, ByteBuffer record, int recordBytes) {
    StatusCode status = file.write(offset, record, ioResult);
    return status.isOk() && ioResult.bytesTransferred() != recordBytes
        ? StatusCode.IO_FAILURE : status;
  }

  void acceptAppend(
      LocalWalReservation reservation,
      LocalWalAppendResult result,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int recordBytes) {
    long start = tailEnd;
    if (pendingRecordCount == 0) pendingStart = start;
    tailEnd += recordBytes;
    result.set(start, tailEnd, nextJournalSequence);
    nextJournalSequence = nextJournalSequence == Long.MAX_VALUE ? 0 : nextJournalSequence + 1;
    pendingRecordCount++;
    if (decisionCode == 1) lastAppendedCommitSequence = commitSequence;
    maximumTransactionId = Math.max(maximumTransactionId, transactionId);
    owner.activeReservationToken = 0;
    reservation.complete();
  }

  void acceptRecordBatchAppend(
      LocalWalGroupAppendResult result, long transactionId, long commitSequence,
      long appendedEnd, int recordCount) {
    long start = tailEnd;
    long firstSequence = nextJournalSequence;
    if (pendingRecordCount == 0) pendingStart = start;
    tailEnd = appendedEnd;
    pendingRecordCount += recordCount;
    nextJournalSequence = firstSequence > Long.MAX_VALUE - recordCount
        ? 0 : firstSequence + recordCount;
    if (commitSequence > 0) lastAppendedCommitSequence = commitSequence;
    maximumTransactionId = Math.max(maximumTransactionId, transactionId);
    result.set(start, tailEnd, firstSequence, recordCount);
  }

  void acceptDecisionBatchAppend(
      LocalWalGroupAppendResult result, LocalWalDecisionBatch batch, long appendedEnd) {
    long start = tailEnd;
    long firstSequence = nextJournalSequence;
    if (pendingRecordCount == 0) pendingStart = start;
    int records = batch.recordCount();
    tailEnd = appendedEnd;
    pendingRecordCount += records;
    nextJournalSequence = firstSequence > Long.MAX_VALUE - records ? 0 : firstSequence + records;
    int transactions = batch.transactionCount();
    lastAppendedCommitSequence = batch.commitSequence(transactions - 1);
    for (int index = 0; index < transactions; index++) {
      maximumTransactionId = Math.max(maximumTransactionId, batch.transactionId(index));
    }
    result.set(start, tailEnd, firstSequence, records);
  }

  StatusCode appendPendingFooter() {
    if (pendingRecordCount <= 0 || commitGroup.recordBytes() <= 0
        || tailEnd > Long.MAX_VALUE - WalCommitGroupCodec.FOOTER_BYTES
        || sealedRecordCount > Long.MAX_VALUE - pendingRecordCount) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int predecessorDigest = commitGroup.digest();
    StatusCode status = WalCommitGroupCodec.encodeReserved(
        pendingStart, commitGroup.recordBytes(), pendingRecordCount,
        commitGroup.firstSequence(), commitGroup.lastSequence(), commitGroup.digest(),
        commitGroup.checksumValue(), commitGroup.footer(), checksum);
    if (!status.isOk()) return status;
    ioResult.reset();
    try {
      status = file.write(tailEnd, commitGroup.footer(), ioResult);
    } catch (Throwable unexpected) {
      owner.markFailed();
      throw unexpected;
    }
    commitGroup.footer().position(0);
    if (status.isOk() && ioResult.bytesTransferred() != WalCommitGroupCodec.FOOTER_BYTES) {
      status = StatusCode.IO_FAILURE;
    }
    if (!status.isOk()) {
      owner.markFailed();
      return status;
    }
    int terminalDigest = WalCommitGroupCodec.chainDigest(commitGroup.footer());
    if (sealedRecordCount == 0) {
      sealedStart = pendingStart;
      sealedPreviousDigest = predecessorDigest;
    }
    sealedRecordCount += pendingRecordCount;
    sealedCommitSequence = lastAppendedCommitSequence;
    sealedDigest = terminalDigest;
    commitGroup.setDigest(terminalDigest);
    tailEnd += WalCommitGroupCodec.FOOTER_BYTES;
    pendingStart = 0;
    pendingRecordCount = 0;
    commitGroup.resetPending();
    return StatusCode.OK;
  }

  void captureSealedValues(LocalWalForceTarget target, long token) {
    target.capture(owner, file, token, sealedStart, tailEnd, sealedRecordCount,
        sealedCommitSequence, sealedPreviousDigest, sealedDigest);
  }

  int durableDigest() { return durableDigest; }
  void clearSealed() {
    sealedStart = sealedRecordCount = sealedCommitSequence = 0;
    sealedPreviousDigest = sealedDigest = 0;
  }
  void setDurableFrontier(LocalWalForceTarget target) {
    durableEnd = target.endOffset();
    lastCommitSequence = target.commitSequence();
    durableDigest = target.finalDigest();
  }
  void restoreCapturedSuffix(LocalWalForceTarget target) {
    sealedStart = target.startOffset();
    sealedRecordCount = target.recordCount();
    sealedCommitSequence = target.commitSequence();
    sealedPreviousDigest = target.previousDigest();
    sealedDigest = target.finalDigest();
  }

  void setRecoveryFrontier(long offset, long sequence) {
    tailEnd = durableEnd = offset;
    nextJournalSequence = sequence;
    commitGroup.resetPending();
    durableDigest = commitGroup.digest();
  }

  void setDigest(int digest) { commitGroup.setDigest(digest); durableDigest = digest; }
  void setMaximumTransactionId(long value) { maximumTransactionId = value; }
  void commitRecoveredGroup(long commitSequence, long transactionId) {
    lastAppendedCommitSequence = commitSequence;
    lastCommitSequence = commitSequence;
    maximumTransactionId = transactionId;
    commitGroup.setDigest(footerDigest());
    durableDigest = commitGroup.digest();
  }
  long lastAppendedCommitSequence() { return lastAppendedCommitSequence; }

  StatusCode adoptFrom(LocalWalAppendState replacement, long checkpointTransactionId) {
    replacement.lastCommitSequence = lastCommitSequence;
    replacement.lastAppendedCommitSequence = lastCommitSequence;
    replacement.maximumTransactionId = Math.max(maximumTransactionId, checkpointTransactionId);
    DurableFile previousFile = file;
    file = replacement.file;
    tailEnd = replacement.tailEnd;
    durableEnd = replacement.durableEnd;
    nextJournalSequence = replacement.nextJournalSequence;
    lastCommitSequence = replacement.lastCommitSequence;
    lastAppendedCommitSequence = replacement.lastAppendedCommitSequence;
    commitGroup.setDigest(replacement.commitGroup.digest());
    durableDigest = replacement.durableDigest;
    maximumTransactionId = replacement.maximumTransactionId;
    copiedPayloadBytes += replacement.copiedPayloadBytes;
    return previousFile.close();
  }

  private static ByteBuffer payloadView(ByteBuffer record) {
    record.position(WalRecordCodec.HEADER_BYTES);
    ByteBuffer payload = record.slice();
    record.clear();
    return payload;
  }
}
