package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Encodes and appends one aggregate-admitted batch of independent transaction decisions. */
final class LocalWalDecisionBatchAppender {
  private LocalWalDecisionBatchAppender() { }

  static StatusCode append(
      LocalWal wal,
      LocalWalGroupReservation reservation,
      long[] transactionIds,
      long[] commitSequences,
      int[] groupEnds,
      int groupCount,
      int formatId,
      int formatVersion,
      LocalWalGroupAppendResult result) {
    if (!valid(wal, reservation, transactionIds, commitSequences, groupEnds, groupCount)
        || result == null || formatId <= 0 || formatVersion <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = encode(
        wal, reservation, transactionIds, commitSequences, groupEnds,
        formatId, formatVersion);
    if (status.isOk()) status = write(wal, reservation);
    if (!status.isOk()) return status;
    wal.acceptDecisionBatchAppend(
        reservation, result, transactionIds, commitSequences, groupCount);
    return StatusCode.OK;
  }

  private static StatusCode encode(
      LocalWal wal, LocalWalGroupReservation reservation,
      long[] transactionIds, long[] commitSequences, int[] groupEnds,
      int formatId, int formatVersion) {
    int group = 0;
    int first = wal.pendingRecordCountValue();
    long sequence = wal.nextJournalSequenceValue();
    for (int record = 0; record < reservation.recordCount(); record++) {
      ByteBuffer payload = reservation.writablePayload(record);
      if (payload.position() != reservation.payloadBytes(record)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      boolean decision = record + 1 == groupEnds[group];
      StatusCode status = WalRecordCodec.encodeReserved(
          sequence + record, transactionIds[group], decision ? commitSequences[group] : 0,
          decision ? 1 : 0, formatId, formatVersion, reservation.payloadBytes(record),
          wal.appendRecordBufferAt(first + record), wal.appendChecksum());
      if (!status.isOk()) return status;
      if (decision) group++;
    }
    return StatusCode.OK;
  }

  private static StatusCode write(LocalWal wal, LocalWalGroupReservation reservation) {
    long offset = wal.tailEnd();
    int first = wal.pendingRecordCountValue();
    for (int record = 0; record < reservation.recordCount(); record++) {
      int bytes = WalRecordCodec.encodedBytes(reservation.payloadBytes(record));
      StatusCode status = wal.writeAppendRecord(
          offset, wal.appendRecordBufferAt(first + record), bytes);
      if (!status.isOk()) {
        wal.abortGroupAppend(reservation);
        return status;
      }
      offset += bytes;
    }
    return StatusCode.OK;
  }

  private static boolean valid(
      LocalWal wal, LocalWalGroupReservation reservation,
      long[] transactionIds, long[] commitSequences, int[] groupEnds, int groupCount) {
    if (reservation == null || transactionIds == null || commitSequences == null
        || groupEnds == null || groupCount <= 0 || groupCount > transactionIds.length
        || groupCount > commitSequences.length || groupCount > groupEnds.length
        || !wal.ownsGroupReservation(reservation) || wal.hasOpenLogicalStream()) return false;
    int previousEnd = 0;
    long expectedSequence = wal.nextCommitSequence();
    if (expectedSequence <= 0) return false;
    for (int group = 0; group < groupCount; group++) {
      if (transactionIds[group] <= 0 || groupEnds[group] <= previousEnd
          || groupEnds[group] > reservation.recordCount()
          || commitSequences[group] != expectedSequence) return false;
      previousEnd = groupEnds[group];
      expectedSequence = expectedSequence == Long.MAX_VALUE ? 0 : expectedSequence + 1;
    }
    return previousEnd == reservation.recordCount();
  }
}
