package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    for (int index = 0; index < 3; index++) {
      assertEquals(StatusCode.OK, wal.readForcedRecord(index, forcedRead));
      assertEquals(index + 10L, forcedRead.payload().getLong(0));
    }
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
  void rejectsWholeGroupWhenPendingSlotsAreInsufficient(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalReservation single = new LocalWalReservation();
    LocalWalAppendResult appended = new LocalWalAppendResult();
    for (int index = 0; index < LocalWal.MAX_PENDING_RECORDS - 1; index++) {
      assertEquals(StatusCode.OK, wal.reserve(1, single));
      single.writablePayload().put((byte) index);
      assertEquals(
          StatusCode.OK,
          wal.appendUnforced(single, 91, 0, 0, 3, 1, appended));
    }
    long tail = wal.tailEnd();
    long sequence = wal.nextJournalSequence();
    LocalWalGroupReservation group = new LocalWalGroupReservation();
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        wal.reserveGroup(new int[] {1, 1}, 2, group));
    assertEquals(tail, wal.tailEnd());
    assertEquals(sequence, wal.nextJournalSequence());
    assertEquals(false, group.isActive());

    LocalWalForceResult forced = new LocalWalForceResult();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(LocalWal.MAX_PENDING_RECORDS - 1, forced.recordCount());
    assertEquals(StatusCode.OK, wal.releaseForcedBatch());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void appendsLogicalGroupContiguouslyWithOneFinalDecision(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalGroupReservation group = new LocalWalGroupReservation();
    assertEquals(StatusCode.OK, wal.reserveGroup(new int[] {4, 8, 1}, 3, group));
    group.writablePayload(0).putInt(101);
    group.writablePayload(1).putLong(103);
    LocalWalGroupAppendResult appended = new LocalWalGroupAppendResult();
    long reservedTail = wal.tailEnd();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        wal.appendGroupUnforced(group, 97, 11, 7, 2, appended));
    assertEquals(reservedTail, wal.tailEnd());
    assertEquals(true, group.isActive());
    group.writablePayload(2).put((byte) 107);
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
    int[] bytes = {1, 1, 1, 1, 1, 1};
    LocalWalGroupReservation reservation = new LocalWalGroupReservation();
    assertEquals(StatusCode.OK, wal.reserveGroup(bytes, bytes.length, reservation));
    for (int record = 0; record < bytes.length; record++) {
      reservation.writablePayload(record).put((byte) (record + 1));
    }
    long[] transactions = {101, 102, 103};
    long[] sequences = {1, 2, 3};
    int[] groupEnds = {2, 3, 6};
    LocalWalGroupAppendResult appended = new LocalWalGroupAppendResult();
    assertEquals(StatusCode.OK, wal.appendDecisionBatchUnforced(
        reservation, transactions, sequences, groupEnds, 3, 7, 2, appended));
    long forces = counters.forceCalls();
    LocalWalForceResult forced = new LocalWalForceResult();
    assertEquals(StatusCode.OK, wal.forcePending(forced));
    assertEquals(forces + 1, counters.forceCalls());
    assertEquals(6, forced.recordCount());
    LocalWalReadResult read = new LocalWalReadResult();
    int group = 0;
    for (int record = 0; record < bytes.length; record++) {
      assertEquals(StatusCode.OK, wal.readForcedRecord(record, read));
      assertEquals(transactions[group], read.header().transactionId());
      boolean decision = record + 1 == groupEnds[group];
      assertEquals(decision ? 1 : 0, read.header().decisionCode());
      assertEquals(decision ? sequences[group] : 0, read.header().commitSequence());
      if (decision) group++;
    }
    assertEquals(StatusCode.OK, wal.releaseForcedBatch());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void forcesContinuationThenFinalGroupAndRecoversDecisionBoundary(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalGroupReservation continuation = new LocalWalGroupReservation();
    assertEquals(StatusCode.OK, wal.reserveGroup(new int[] {4, 4}, continuation));
    continuation.writablePayload(0).putInt(201);
    continuation.writablePayload(1).putInt(203);
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

    LocalWalGroupReservation decision = new LocalWalGroupReservation();
    assertEquals(StatusCode.OK, wal.reserveGroup(new int[] {8, 1}, decision));
    decision.writablePayload(0).putLong(205);
    decision.writablePayload(1).put((byte) 207);
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
    LocalWalGroupReservation continuation = new LocalWalGroupReservation();
    assertEquals(StatusCode.OK, wal.reserveGroup(new int[] {2, 3, 1}, continuation));
    continuation.writablePayload(0).putShort((short) 211);
    continuation.writablePayload(1).put(new byte[] {1, 2, 3});
    continuation.writablePayload(2).put((byte) 5);
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
  void cancelsGroupReservationWithoutConsumingSlots(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    LocalWalGroupReservation group = new LocalWalGroupReservation();
    assertEquals(StatusCode.OK, wal.reserveGroup(new int[] {2, 3}, 2, group));
    long tail = wal.tailEnd();
    assertEquals(StatusCode.OK, wal.cancelGroup(group));
    assertEquals(false, group.isActive());
    assertEquals(tail, wal.tailEnd());

    LocalWalReservation single = reserve(wal, new byte[] {5});
    LocalWalAppendResult appended = new LocalWalAppendResult();
    assertEquals(StatusCode.OK, wal.publish(single, 1, 1, 1, 1, 1, appended));
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
    LocalWalGroupReservation reservation = new LocalWalGroupReservation();
    assertEquals(StatusCode.OK,
        first.reserveLogicalStreamBatch(owned, new int[] {1}, 1, reservation));
    reservation.writablePayload(0).put((byte) 1);
    assertEquals(StatusCode.CONFLICT,
        first.appendLogicalStreamContinuation(foreign, reservation, append));
    assertEquals(StatusCode.OK, first.cancelLogicalStreamBatch(owned, reservation));
    assertEquals(StatusCode.OK, first.cancelLogicalStream(owned));
    assertEquals(StatusCode.CONFLICT,
        first.appendLogicalStreamContinuation(owned, reservation, append));
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
}
