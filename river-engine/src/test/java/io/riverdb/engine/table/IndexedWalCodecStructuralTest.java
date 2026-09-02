package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalOpenResult;
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedWalCodecStructuralTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(991, 997);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void commonHeaderAndFixedFieldsRejectStructuralCorruption() {
    int pageBytes = IndexedWalCodec.pageOperationBytes(1, 1);
    ByteBuffer pages = ByteBuffer.allocate(pageBytes);
    IndexedWalCodec.encodePageOperationHeader(pages, 1, 1);
    int versionOffset = IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES + PageCodec.PAGE_BYTES;
    IndexedWalCodec.encodePageOperationVersion(pages, versionOffset, 0, false);
    assertEquals(StatusCode.OK, IndexedWalCodec.validatePageOperation(pages, 2, 2));
    assertTrue(IndexedWalCodec.validPageOperationVersion(pages, versionOffset));

    IndexedWalCodec.putLong(pages, 0, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validatePageOperation(pages, 2, 2));
    IndexedWalCodec.putLong(pages, 0, IndexedWalCodec.OPERATION_MAGIC);
    IndexedWalCodec.putInt(pages, 8, IndexedWalCodec.FORMAT_VERSION + 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validatePageOperation(pages, 2, 2));
    IndexedWalCodec.putInt(pages, 8, IndexedWalCodec.FORMAT_VERSION);
    IndexedWalCodec.putInt(pages, 12, IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validatePageOperation(pages, 2, 2));
    IndexedWalCodec.putInt(pages, 12, IndexedWalCodec.OPERATION_TYPE_PAGE_IMAGES);

    IndexedWalCodec.putInt(pages, 16, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validatePageOperation(pages, 2, 2));
    IndexedWalCodec.putInt(pages, 16, 1);
    IndexedWalCodec.putInt(pages, 20, 3);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validatePageOperation(pages, 2, 2));
    IndexedWalCodec.putInt(pages, 20, 1);
    IndexedWalCodec.putInt(pages, versionOffset, -1);
    assertFalse(IndexedWalCodec.validPageOperationVersion(pages, versionOffset));
    IndexedWalCodec.putInt(pages, versionOffset, 0);
    IndexedWalCodec.putInt(pages, versionOffset + 4, 2);
    assertFalse(IndexedWalCodec.validPageOperationVersion(pages, versionOffset));
    assertFalse(IndexedWalCodec.validPageOperationVersion(pages, pageBytes));
  }

  @Test
  void vacuumStructureAndDuplicatePageDetectionAreBounded() {
    ByteBuffer chunk = ByteBuffer.allocate(
        IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES + IndexedWalCodec.vacuumEntryBytes(1));
    IndexedWalCodec.encodeVacuumChunkHeader(chunk, 1, 0, 1, 0, 1);
    int entry = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    IndexedWalCodec.encodeVacuumEntry(chunk, entry, 3, 17, 1, 1, false);
    assertEquals(StatusCode.OK, IndexedWalCodec.validateVacuumChunk(chunk, 1, 1));
    assertTrue(IndexedWalCodec.validVacuumEntry(chunk, entry));
    assertEquals(3, IndexedWalCodec.vacuumEntrySpace(chunk, entry));

    IndexedWalCodec.putInt(chunk, 24, 2);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumChunk(chunk, 1, 1));
    IndexedWalCodec.putInt(chunk, 24, 1);
    IndexedWalCodec.putInt(chunk, 32, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumChunk(chunk, 1, 1));
    IndexedWalCodec.putInt(chunk, 32, 1);
    IndexedWalCodec.putInt(chunk, 36, 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumChunk(chunk, 1, 1));
    IndexedWalCodec.putInt(chunk, 36, 0);
    IndexedWalCodec.putInt(chunk, entry + 16, 2);
    assertFalse(IndexedWalCodec.validVacuumEntry(chunk, entry));
    IndexedWalCodec.putInt(chunk, entry + 16, 0);
    IndexedWalCodec.putInt(chunk, entry + 20, -1);
    assertFalse(IndexedWalCodec.validVacuumEntry(chunk, entry));
    IndexedWalCodec.putInt(chunk, entry + 20, 0);
    IndexedWalCodec.putInt(chunk, entry + 12, chunk.limit());
    assertFalse(IndexedWalCodec.validVacuumEntry(chunk, entry));
    assertFalse(IndexedWalCodec.validVacuumEntry(chunk, chunk.limit()));

    ByteBuffer commit = ByteBuffer.allocate(IndexedWalCodec.VACUUM_COMMIT_PAYLOAD_BYTES);
    IndexedWalCodec.encodeVacuumCommit(commit, 1, 1, 2);
    assertEquals(StatusCode.OK, IndexedWalCodec.validateVacuumCommit(commit, 2, 1));
    IndexedWalCodec.putInt(commit, 20, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumCommit(commit, 2, 1));
    IndexedWalCodec.putInt(commit, 20, 1);
    IndexedWalCodec.putInt(commit, 24, 0);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumCommit(commit, 2, 1));
    IndexedWalCodec.putInt(commit, 24, 2);
    IndexedWalCodec.putInt(commit, 28, 1);
    assertEquals(StatusCode.CORRUPTION, IndexedWalCodec.validateVacuumCommit(commit, 2, 1));

    int[] pageIds = {3, 7};
    assertFalse(IndexedWalCodec.containsEarlierPageId(pageIds, 0, 3));
    assertTrue(IndexedWalCodec.containsEarlierPageId(pageIds, 1, 3));
    assertFalse(IndexedWalCodec.containsEarlierPageId(pageIds, 2, 5));
    assertTrue(IndexedWalCodec.containsEarlierPageId(pageIds, 2, 7));
  }

  @Test
  void longMaximumSpaceRoundTripsAcrossVacuumWalShape() {
    ByteBuffer vacuum = ByteBuffer.allocate(
        IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES + IndexedWalCodec.vacuumEntryBytes(1));
    IndexedWalCodec.encodeVacuumChunkHeader(vacuum, 1, 0, 1, 0, 1);
    int vacuumEntry = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    IndexedWalCodec.encodeVacuumEntry(
        vacuum, vacuumEntry, Long.MAX_VALUE, 10, 4, 1, false);
    assertEquals(StatusCode.OK, IndexedWalCodec.validateVacuumChunk(vacuum, 1, 1));
    assertEquals(Long.MAX_VALUE,
        IndexedWalCodec.vacuumEntrySpace(vacuum, vacuumEntry));
  }

  @Test
  void obsoleteWalHeaderVersionFailsClosedDuringRecovery(@TempDir Path root) {
    assertEquals(7, IndexedTableStore.WAL_FORMAT_VERSION);
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, walResult));
    LocalWal wal = walResult.wal();
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.create(directory, wal, DATABASE, GENERATION, created));
    assertEquals(StatusCode.OK, created.store().close());

    LocalWalReservation reservation = new LocalWalReservation();
    assertEquals(StatusCode.OK, wal.reserve(1, reservation));
    reservation.writablePayload().put((byte) 0);
    assertEquals(StatusCode.OK, wal.publish(
        reservation, 2, wal.currentCommitSequence() + 1, 1,
        IndexedTableStore.WAL_FORMAT_ID, 6, new LocalWalAppendResult()));

    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.CORRUPTION,
        IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, reopened));
    assertNull(reopened.store());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }
}
