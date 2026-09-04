package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalWalTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(101, 103);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void appendsForcesReopensAndReads(@TempDir Path root) {
    byte[] expected = {9, 2, 6, 5, 3, 5};
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalAppendResult appended = new LocalWalAppendResult();
    LocalWalReservation reservation = reserve(wal, expected);
    assertEquals(StatusCode.OK, wal.publish(reservation, 41, 43, 1, 7, 1, appended));
    assertEquals(1, appended.journalSequence());
    assertEquals(64, appended.startOffset());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    assertEquals(44, wal.nextCommitSequence());
    assertEquals(42, wal.nextTransactionId());
    LocalWalReadResult read = new LocalWalReadResult();
    assertEquals(StatusCode.OK, wal.read(appended.startOffset(), read));
    byte[] actual = new byte[expected.length];
    read.payload().get(actual);
    assertArrayEquals(expected, actual);
    assertEquals(41, read.header().transactionId());
    assertEquals(43, read.header().commitSequence());
    assertEquals(appended.endOffset(), read.nextOffset());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void attributesForcesByCauseAndReconcilesTotals(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalMetrics before = new LocalWalMetrics();
    LocalWalMetrics after = new LocalWalMetrics();
    assertEquals(StatusCode.OK, wal.copyMetrics(before));

    LocalWalForceCause[] causes = {
      LocalWalForceCause.SHARED_GROUP,
      LocalWalForceCause.DIRECT_COMMIT,
      LocalWalForceCause.CHECKPOINT
    };
    for (int index = 0; index < causes.length; index++) {
      LocalWalReservation reservation = reserve(wal, new byte[] {(byte) index});
      LocalWalAppendResult appended = new LocalWalAppendResult();
      assertEquals(StatusCode.OK, wal.appendUnforced(
          reservation, index + 2, index + 1, 1, 1, 1, appended));
      LocalWalForceResult forced = new LocalWalForceResult();
      assertEquals(StatusCode.OK, wal.forcePending(forced, causes[index]));
      assertEquals(StatusCode.OK, wal.releaseForcedBatch());
    }

    assertEquals(StatusCode.OK, wal.copyMetrics(after));
    assertTrue(after.reconciles());
    assertEquals(causes.length, after.totalForceCount() - before.totalForceCount());
    assertEquals(
        after.totalForceCount() - before.totalForceCount(),
        causeDelta(after, before, LocalWalForceCause.SHARED_GROUP)
            + causeDelta(after, before, LocalWalForceCause.DIRECT_COMMIT)
            + causeDelta(after, before, LocalWalForceCause.CHECKPOINT));
    for (LocalWalForceCause cause : causes) {
      assertEquals(1, causeDelta(after, before, cause));
      assertEquals(1, statusDelta(after, before, cause, StatusCode.OK));
      assertTrue(after.forceBytes(cause) >= before.forceBytes(cause));
      assertTrue(after.forceNanos(cause) >= before.forceNanos(cause));
    }
    assertTrue(after.totalForceBytes() >= before.totalForceBytes());
    assertTrue(after.totalForceNanos() >= before.totalForceNanos());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void attributesGenerationHeaderForceToCheckpoint(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalMetrics before = new LocalWalMetrics();
    LocalWalMetrics after = new LocalWalMetrics();
    assertEquals(StatusCode.OK, wal.copyMetrics(before));

    WalGeneration next = WalGeneration.of(GENERATION.value() + 1);
    assertEquals(StatusCode.OK, wal.rotate(
        directory, LocalWal.generationFileName(next), next, 1));

    assertEquals(StatusCode.OK, wal.copyMetrics(after));
    assertTrue(after.reconciles());
    assertEquals(1, after.totalForceCount() - before.totalForceCount());
    assertEquals(1, causeDelta(after, before, LocalWalForceCause.CHECKPOINT));
    assertEquals(0, causeDelta(after, before, LocalWalForceCause.OTHER));
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void truncatesInvalidTailAndContinuesSequence(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalAppendResult first = new LocalWalAppendResult();
    LocalWalReservation reservation = reserve(wal, new byte[] {1, 2, 3});
    assertEquals(StatusCode.OK, wal.publish(reservation, 1, 0, 0, 1, 1, first));
    assertEquals(StatusCode.OK, wal.close());

    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.reopen(LocalWal.FILE_NAME, operation));
    DurableFile raw = operation.file();
    IoResult io = new IoResult();
    assertEquals(
        StatusCode.OK,
        raw.write(first.endOffset(), ByteBuffer.wrap(new byte[] {8, 8, 8, 8, 8}), io));
    assertEquals(StatusCode.OK, raw.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, raw.close());

    wal = openWal(directory);
    assertEquals(first.endOffset(), wal.tailEnd());
    assertEquals(2, wal.nextJournalSequence());
    LocalWalAppendResult second = new LocalWalAppendResult();
    reservation.reset();
    reservation = reserve(wal, new byte[] {4, 5});
    assertEquals(StatusCode.OK, wal.publish(reservation, 2, 0, 0, 1, 1, second));
    assertEquals(2, second.journalSequence());
    assertEquals(StatusCode.OK, wal.close());

    assertEquals(StatusCode.OK, directory.reopen(LocalWal.FILE_NAME, operation));
    raw = operation.file();
    assertEquals(
        StatusCode.OK,
        raw.write(second.startOffset() + 64, ByteBuffer.wrap(new byte[] {0}), io));
    assertEquals(StatusCode.OK, raw.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, raw.close());

    LocalWalOpenResult corrupted = new LocalWalOpenResult();
    assertEquals(
        StatusCode.CORRUPTION,
        LocalWal.open(directory, DATABASE, GENERATION, corrupted));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void reservationUsesProviderStorageWithoutPayloadCopy(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalReservation reservation = new LocalWalReservation();
    assertEquals(StatusCode.OK, wal.reserve(4, reservation));
    ByteBuffer providerStorage = reservation.writablePayload();
    providerStorage.putInt(0x01020304);
    LocalWalAppendResult appended = new LocalWalAppendResult();
    assertEquals(StatusCode.OK, wal.publish(reservation, 0, 0, 0, 1, 1, appended));
    assertEquals(0, wal.copiedPayloadBytes());

    LocalWalReadResult read = new LocalWalReadResult();
    assertEquals(StatusCode.OK, wal.read(appended.startOffset(), read));
    assertEquals(0x01020304, read.payload().getInt());
    assertEquals(0, wal.copiedPayloadBytes());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void appendsSeveralRecordsBeforeOneDurableForce(@TempDir Path root) {
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(root, counters);
    LocalWal wal = openWal(directory);
    long initialForces = counters.forceCalls();
    LocalWalReservation reservation = new LocalWalReservation();
    LocalWalAppendResult appended = new LocalWalAppendResult();
    long firstStart = 0;
    for (int index = 0; index < 3; index++) {
      assertEquals(StatusCode.OK, wal.reserve(Long.BYTES, reservation));
      reservation.writablePayload().putLong(index + 10L);
      assertEquals(
          StatusCode.OK,
          wal.appendUnforced(reservation, index + 20L, index + 1L, 1, 7, 1, appended));
      if (index == 0) {
        firstStart = appended.startOffset();
      }
      assertEquals(WalFileHeaderCodec.HEADER_BYTES, wal.durableEnd());
      assertEquals(0, wal.currentCommitSequence());
      assertEquals(
          StatusCode.INVALID_EXTERNAL_INPUT,
          wal.read(firstStart, new LocalWalReadResult()));
    }
    assertEquals(StatusCode.CONFLICT, wal.close());
    assertEquals(initialForces, counters.forceCalls());
    LocalWalForceResult forced = new LocalWalForceResult();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(3, forced.recordCount());
    assertEquals(firstStart, forced.startOffset());
    assertEquals(appended.endOffset(), forced.durableEnd());
    assertEquals(3, forced.commitSequence());
    assertEquals(appended.endOffset(), wal.durableEnd());
    assertEquals(3, wal.currentCommitSequence());
    assertEquals(initialForces + 1, counters.forceCalls());

    LocalWalReadResult forcedRead = new LocalWalReadResult();
    LocalWalForcedCursor cursor = new LocalWalForcedCursor();
    assertEquals(StatusCode.OK, wal.openForcedCursor(cursor));
    for (int index = 0; index < 3; index++) {
      assertEquals(StatusCode.OK, cursor.next(forcedRead));
      assertEquals(index + 10L, forcedRead.payload().getLong(0));
    }
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, wal.releaseForcedBatch());

    long offset = firstStart;
    LocalWalReadResult read = new LocalWalReadResult();
    for (int index = 0; index < 3; index++) {
      assertEquals(StatusCode.OK, wal.read(offset, read));
      assertEquals(index + 10L, read.payload().getLong(0));
      assertEquals(index + 1L, read.header().commitSequence());
      offset = read.nextOffset();
    }
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void growsPendingBatchPastTheFormerFixedSlotBoundary(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalReservation single = new LocalWalReservation();
    LocalWalAppendResult appended = new LocalWalAppendResult();
    int records = 33;
    for (int index = 0; index < records; index++) {
      assertEquals(StatusCode.OK, wal.reserve(1, single));
      single.writablePayload().put((byte) index);
      assertEquals(
          StatusCode.OK,
          wal.appendUnforced(single, 91, 0, 0, 3, 1, appended));
    }
    LocalWalForceResult forced = new LocalWalForceResult();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(records, forced.recordCount());
    assertEquals(StatusCode.OK, wal.releaseForcedBatch());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void appendsLogicalGroupContiguouslyWithOneFinalDecision(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalRecordBatch group = new BytesBatch(
        ByteBuffer.allocate(4).putInt(101).array(),
        ByteBuffer.allocate(8).putLong(103).array(),
        new byte[] {107});
    LocalWalGroupAppendResult appended = new LocalWalGroupAppendResult();
    assertEquals(StatusCode.OK, wal.appendGroupUnforced(group, 97, 11, 7, 2, appended));
    assertEquals(3, appended.recordCount());
    assertEquals(1, appended.firstJournalSequence());

    LocalWalForceResult forced = new LocalWalForceResult();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(3, forced.recordCount());
    assertEquals(11, forced.commitSequence());
    long expectedOffset = appended.startOffset();
    LocalWalReadResult read = new LocalWalReadResult();
    for (int index = 0; index < 3; index++) {
      assertEquals(StatusCode.OK, wal.read(expectedOffset, read));
      assertEquals(index + 1L, read.header().journalSequence());
      assertEquals(97, read.header().transactionId());
      assertEquals(7, read.header().formatId());
      assertEquals(2, read.header().formatVersion());
      assertEquals(index == 2 ? 1 : 0, read.header().decisionCode());
      assertEquals(index == 2 ? 11 : 0, read.header().commitSequence());
      expectedOffset = read.nextOffset();
    }
    assertEquals(appended.endOffset(), expectedOffset);
    assertEquals(StatusCode.OK, wal.releaseForcedBatch());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void aggregateAdmissionAppendsIndependentTwoOneThreeDecisionsWithOneForce(
      @TempDir Path root) {
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(root, counters);
    LocalWal wal = openWal(directory);
    byte[][] payloads = {
      {1}, {2}, {3}, {4}, {5}, {6}
    };
    long[] transactions = {101, 102, 103};
    long[] sequences = {1, 2, 3};
    int[] groupEnds = {2, 3, 6};
    LocalWalDecisionBatch batch = new TestDecisionBatch(
        payloads, transactions, sequences, groupEnds);
    LocalWalGroupAppendResult appended = new LocalWalGroupAppendResult();
    assertEquals(StatusCode.OK, wal.appendDecisionBatchUnforced(
        batch, 7, 2, appended));
    long forces = counters.forceCalls();
    LocalWalForceResult forced = new LocalWalForceResult();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(forces + 1, counters.forceCalls());
    assertEquals(6, forced.recordCount());
    LocalWalReadResult read = new LocalWalReadResult();
    LocalWalForcedCursor cursor = new LocalWalForcedCursor();
    assertEquals(StatusCode.OK, wal.openForcedCursor(cursor));
    int group = 0;
    for (int record = 0; record < payloads.length; record++) {
      assertEquals(StatusCode.OK, cursor.next(read));
      assertEquals(transactions[group], read.header().transactionId());
      boolean decision = record + 1 == groupEnds[group];
      assertEquals(decision ? 1 : 0, read.header().decisionCode());
      assertEquals(decision ? sequences[group] : 0, read.header().commitSequence());
      if (decision) group++;
    }
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, wal.releaseForcedBatch());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void decisionBatchReportsPhysicalProgressWhenLaterEncodingFails(
      @TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalDecisionBatch batch = new FailingDecisionBatch(
        new byte[][] {{1}, {2}},
        new long[] {101, 102},
        new long[] {1, 2},
        new int[] {1, 2},
        1);
    LocalWalGroupAppendResult appended = new LocalWalGroupAppendResult();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        wal.appendDecisionBatchUnforced(batch, 7, 2, appended));
    assertEquals(
        LocalWalAppendDisposition.STORAGE_MAY_HAVE_CHANGED,
        appended.disposition());
    assertEquals(StatusCode.FENCED,
        wal.reserve(1, new LocalWalReservation()));
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void fencesForcedDecisionBatchWhenPublicationCannotComplete(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalReservation reservation = reserve(wal, new byte[] {7});
    LocalWalAppendResult appended = new LocalWalAppendResult();
    assertEquals(StatusCode.OK, wal.appendUnforced(
        reservation, 101, 1, 1, 7, 1, appended));
    LocalWalForceResult forced = new LocalWalForceResult();
    assertEquals(StatusCode.OK, wal.forcePending(forced));

    assertEquals(StatusCode.OK, wal.fencePendingBatch());
    assertEquals(StatusCode.FENCED, wal.reserve(1, new LocalWalReservation()));
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void forcesContinuationThenFinalGroupAndRecoversDecisionBoundary(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalRecordBatch continuation = new BytesBatch(
        ByteBuffer.allocate(4).putInt(201).array(),
        ByteBuffer.allocate(4).putInt(203).array());
    LocalWalGroupAppendResult continued = new LocalWalGroupAppendResult();
    assertEquals(StatusCode.OK,
        wal.appendContinuationGroupUnforced(continuation, 197, 9, 3, continued));
    assertEquals(1, continued.firstJournalSequence());
    assertEquals(1, wal.nextCommitSequence());
    assertEquals(197, wal.maximumTransactionId());
    LocalWalForceResult forced = new LocalWalForceResult();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(0, forced.commitSequence());
    assertEquals(StatusCode.OK, wal.releaseForcedBatch());

    LocalWalRecordBatch decision = new BytesBatch(
        ByteBuffer.allocate(8).putLong(205).array(), new byte[] {(byte) 207});
    LocalWalGroupAppendResult decided = new LocalWalGroupAppendResult();
    assertEquals(StatusCode.OK, wal.appendGroupUnforced(decision, 197, 7, 9, 3, decided));
    assertEquals(3, decided.firstJournalSequence());
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(7, forced.commitSequence());
    assertEquals(StatusCode.OK, wal.releaseForcedBatch());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    assertEquals(5, wal.nextJournalSequence());
    assertEquals(8, wal.nextCommitSequence());
    assertEquals(197, wal.maximumTransactionId());
    long offset = continued.startOffset();
    LocalWalReadResult read = new LocalWalReadResult();
    for (int index = 0; index < 4; index++) {
      assertEquals(StatusCode.OK, wal.read(offset, read));
      assertEquals(index + 1L, read.header().journalSequence());
      assertEquals(197, read.header().transactionId());
      assertEquals(index == 3 ? 1 : 0, read.header().decisionCode());
      assertEquals(index == 3 ? 7 : 0, read.header().commitSequence());
      offset = read.nextOffset();
    }
    assertEquals(decided.endOffset(), offset);
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void reopensContinuationOnlyCrashImageWithoutInventingDecision(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalRecordBatch continuation = new BytesBatch(
        ByteBuffer.allocate(2).putShort((short) 211).array(),
        new byte[] {1, 2, 3},
        new byte[] {5});
    LocalWalGroupAppendResult appended = new LocalWalGroupAppendResult();
    assertEquals(StatusCode.OK,
        wal.appendContinuationGroupUnforced(continuation, 211, 5, 4, appended));
    LocalWalForceResult forced = new LocalWalForceResult();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(0, forced.commitSequence());
    assertEquals(StatusCode.OK, wal.releaseForcedBatch());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    assertEquals(0, wal.currentCommitSequence());
    assertEquals(1, wal.nextCommitSequence());
    assertEquals(212, wal.nextTransactionId());
    assertEquals(4, wal.nextJournalSequence());
    long offset = appended.startOffset();
    LocalWalReadResult read = new LocalWalReadResult();
    for (int index = 0; index < 3; index++) {
      assertEquals(StatusCode.OK, wal.read(offset, read));
      assertEquals(0, read.header().decisionCode());
      assertEquals(0, read.header().commitSequence());
      offset = read.nextOffset();
    }
    assertEquals(appended.endOffset(), offset);
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsNullStaleAndForeignLogicalStreamCapabilities(@TempDir Path root)
      throws Exception {
    NioDurableDirectory firstDirectory = openDirectory(
        java.nio.file.Files.createDirectory(root.resolve("first")));
    NioDurableDirectory secondDirectory = openDirectory(
        java.nio.file.Files.createDirectory(root.resolve("second")));
    LocalWal first = openWal(firstDirectory);
    LocalWal second = openWal(secondDirectory);
    LocalWalGroupAppendResult append = new LocalWalGroupAppendResult();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        first.appendLogicalStreamContinuation(null, null, append));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        first.appendLogicalStreamFinal(null, null, 1, append));

    LocalWalLogicalStream owned = new LocalWalLogicalStream();
    LocalWalLogicalStream foreign = new LocalWalLogicalStream();
    assertEquals(StatusCode.OK, first.beginLogicalStream(301, 7, 1, owned));
    assertEquals(StatusCode.OK, second.beginLogicalStream(303, 7, 1, foreign));
    LocalWalRecordBatch batch = new BytesBatch(new byte[] {1});
    assertEquals(StatusCode.CONFLICT,
        first.appendLogicalStreamContinuation(foreign, batch, append));
    assertEquals(StatusCode.OK, first.cancelLogicalStream(owned));
    assertEquals(StatusCode.CONFLICT,
        first.appendLogicalStreamContinuation(owned, batch, append));
    assertEquals(StatusCode.OK, second.cancelLogicalStream(foreign));
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
    assertEquals(StatusCode.OK, firstDirectory.close());
    assertEquals(StatusCode.OK, secondDirectory.close());
  }

  @Test
  void enforcesCommitSequenceOrderWithoutConsumingReservation(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalAppendResult appended = new LocalWalAppendResult();
    LocalWalReservation reservation = reserve(wal, new byte[] {1});
    assertEquals(StatusCode.OK, wal.publish(reservation, 7, 2, 1, 1, 1, appended));
    reservation.reset();
    reservation = reserve(wal, new byte[] {2});
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        wal.publish(reservation, 8, 2, 1, 1, 1, appended));
    assertEquals(StatusCode.OK, wal.publish(reservation, 8, 3, 1, 1, 1, appended));
    assertEquals(4, wal.nextCommitSequence());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsWalFromAnotherDatabaseOrGeneration(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    assertEquals(StatusCode.OK, wal.close());

    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(
        StatusCode.FENCED,
        LocalWal.open(
            directory,
            DatabaseIncarnation.of(107, 109),
            GENERATION,
            result));
    assertEquals(
        StatusCode.FENCED,
        LocalWal.open(directory, DATABASE, WalGeneration.of(2), result));
    assertEquals(StatusCode.OK, directory.close());
  }

  private static NioDurableDirectory openDirectory(Path root) {
    return openDirectory(root, new NioIoCounters());
  }

  private static NioDurableDirectory openDirectory(Path root, NioIoCounters counters) {
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

  private static LocalWal openWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static LocalWalReservation reserve(LocalWal wal, byte[] payload) {
    LocalWalReservation reservation = new LocalWalReservation();
    assertEquals(StatusCode.OK, wal.reserve(payload.length, reservation));
    reservation.writablePayload().put(payload);
    return reservation;
  }

  private static long causeDelta(
      LocalWalMetrics after, LocalWalMetrics before, LocalWalForceCause cause) {
    return after.forceCount(cause) - before.forceCount(cause);
  }

  private static long statusDelta(
      LocalWalMetrics after,
      LocalWalMetrics before,
      LocalWalForceCause cause,
      StatusCode status) {
    return after.forceStatusCount(cause, status) - before.forceStatusCount(cause, status);
  }

  private static class BytesBatch implements LocalWalRecordBatch {
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

  private static class TestDecisionBatch extends BytesBatch
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

  private static final class FailingDecisionBatch extends TestDecisionBatch {
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
