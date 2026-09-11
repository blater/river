package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalCommitGroupCodec;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.file.Path;

final class LocalWalTestSupport {
  static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(101, 103);
  static final WalGeneration GENERATION = WalGeneration.of(1);

  static NioDurableDirectory openDirectory(Path root) {
    return openDirectory(root, new NioIoCounters());
  }

  static NioDurableDirectory openDirectory(Path root, NioIoCounters counters) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            counters,
            8,
            result));
    return result.directory();
  }

  static LocalWal openWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  static LocalWalReservation reserve(LocalWal wal, byte[] payload) {
    LocalWalReservation reservation = new LocalWalReservation();
    assertEquals(StatusCode.OK, wal.reserve(payload.length, reservation));
    reservation.writablePayload().put(payload);
    return reservation;
  }

  static LocalWalAppendResult appendAndForce(
      LocalWal wal, long transactionId, long commitSequence, byte[] payload) {
    LocalWalReservation reservation = reserve(wal, payload);
    LocalWalAppendResult appended = new LocalWalAppendResult();
    assertEquals(StatusCode.OK, wal.appendUnforced(
        reservation, transactionId, commitSequence, 1, 7, 1, appended));
    LocalWalForceTarget forced = new LocalWalForceTarget();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(StatusCode.OK, wal.releaseForcedBatch(forced, forced.token()));
    return appended;
  }

  static void assertFooterStraddlesBoundary(Path root, long boundary) {
    long footerStart = boundary - WalCommitGroupCodec.FOOTER_BYTES / 2;
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    BoundaryGroup group = appendUntilFooterStart(wal, footerStart, 1 * 1024 * 1024);
    assertEquals(footerStart, group.recordEnd);

    LocalWalForceTarget forced = new LocalWalForceTarget();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(group.firstStart, forced.startOffset());
    assertEquals(footerStart + WalCommitGroupCodec.FOOTER_BYTES, forced.endOffset());
    assertEquals(group.recordCount, forced.recordCount());
    assertEquals(StatusCode.OK, wal.releaseForcedBatch(forced, forced.token()));
    assertEquals(
        footerStart + WalCommitGroupCodec.FOOTER_BYTES, wal.durableEnd());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    assertEquals(group.recordCount + 1, wal.nextJournalSequence());
    LocalWalReadResult read = new LocalWalReadResult();
    long offset = group.firstStart;
    for (int index = 0; index < group.recordCount; index++) {
      assertEquals(StatusCode.OK, wal.read(offset, read));
      offset = read.nextOffset();
    }
    assertEquals(footerStart + WalCommitGroupCodec.FOOTER_BYTES, offset);
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  static BoundaryGroup appendUntilFooterStart(
      LocalWal wal, long footerStart, int maximumPayloadBytes) {
    long offset = wal.tailEnd();
    long firstStart = 0;
    int recordCount = 0;
    while (offset < footerStart) {
      long remaining = footerStart - offset;
      int payloadBytes = (int) Math.min(
          maximumPayloadBytes, remaining - WalRecordCodec.HEADER_BYTES);
      if (payloadBytes < 0) {
        throw new AssertionError("boundary cannot hold a complete WAL record");
      }
      LocalWalReservation reservation = reserve(wal, new byte[payloadBytes]);
      LocalWalAppendResult appended = new LocalWalAppendResult();
      assertEquals(StatusCode.OK, wal.appendUnforced(
          reservation, 1_000L + recordCount, 0, 0, 1, 1, appended));
      if (recordCount == 0) firstStart = appended.startOffset();
      offset = appended.endOffset();
      recordCount++;
    }
    return new BoundaryGroup(firstStart, offset, recordCount);
  }

  static long causeDelta(
      LocalWalMetrics after, LocalWalMetrics before, LocalWalForceCause cause) {
    return after.forceCount(cause) - before.forceCount(cause);
  }

  static long statusDelta(
      LocalWalMetrics after,
      LocalWalMetrics before,
      LocalWalForceCause cause,
      StatusCode status) {
    return after.forceStatusCount(cause, status) - before.forceStatusCount(cause, status);
  }

  static class BytesBatch implements LocalWalRecordBatch {
    private final byte[][] payloads;

    BytesBatch(byte[]... recordPayloads) {
      payloads = recordPayloads;
    }

    @Override
    public int recordCount() {
      return payloads.length;
    }

    @Override
    public int payloadBytes(int record) {
      return payloads[record].length;
    }

    @Override
    public StatusCode encodePayload(int record, ByteBuffer target) {
      target.put(payloads[record]);
      return StatusCode.OK;
    }
  }

  static final class RepeatedBatch implements LocalWalRecordBatch {
    private final int records;
    private final byte[] payload;

    RepeatedBatch(int records, int payloadBytes) {
      this.records = records;
      payload = new byte[payloadBytes];
    }

    @Override
    public int recordCount() { return records; }

    @Override
    public int payloadBytes(int record) { return payload.length; }

    @Override
    public StatusCode encodePayload(int record, ByteBuffer target) {
      target.put(payload);
      return StatusCode.OK;
    }
  }

  static final class BoundaryGroup {
    private final long firstStart;
    private final long recordEnd;
    private final int recordCount;

    BoundaryGroup(long firstStart, long recordEnd, int recordCount) {
      this.firstStart = firstStart;
      this.recordEnd = recordEnd;
      this.recordCount = recordCount;
    }
  }

  static class TestDecisionBatch extends BytesBatch
      implements LocalWalDecisionBatch {
    private final long[] transactionIds;
    private final long[] commitSequences;
    private final int[] transactionEnds;

    TestDecisionBatch(
        byte[][] payloads,
        long[] transactions,
        long[] sequences,
        int[] ends) {
      super(payloads);
      transactionIds = transactions;
      commitSequences = sequences;
      transactionEnds = ends;
    }

    @Override
    public int transactionCount() {
      return transactionIds.length;
    }

    @Override
    public int transactionEndRecord(int transaction) {
      return transactionEnds[transaction];
    }

    @Override
    public long transactionId(int transaction) {
      return transactionIds[transaction];
    }

    @Override
    public long commitSequence(int transaction) {
      return commitSequences[transaction];
    }
  }

  static final class FailingDecisionBatch extends TestDecisionBatch {
    private final int failingRecord;

    FailingDecisionBatch(
        byte[][] payloads,
        long[] transactions,
        long[] sequences,
        int[] ends,
        int failureRecord) {
      super(payloads, transactions, sequences, ends);
      failingRecord = failureRecord;
    }

    @Override
    public StatusCode encodePayload(int record, ByteBuffer target) {
      return record == failingRecord
          ? StatusCode.INVALID_EXTERNAL_INPUT : super.encodePayload(record, target);
    }
  }
}
