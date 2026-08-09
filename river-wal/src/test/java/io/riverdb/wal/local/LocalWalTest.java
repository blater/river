package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
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
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
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
