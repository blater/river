package io.riverdb.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.control.ControlFile;
import io.riverdb.format.control.ControlFileCodec;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.wal.WalFileHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalRecordCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OfflineDatabaseInspectorTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(31, 37);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void validatesEveryRecognizedPhysicalFile(@TempDir Path root) throws IOException {
    writeDatabase(root);
    Files.write(root.resolve("operator-notes"), new byte[] {1});

    DatabaseInspectionResult result = new DatabaseInspectionResult();
    assertEquals(StatusCode.OK, new OfflineDatabaseInspector().inspect(root, result));
    assertEquals(true, result.isAvailable());
    assertEquals(DATABASE, result.database());
    assertEquals(1, result.walFileCount());
    assertEquals(1, result.walRecordCount());
    assertEquals(1, result.lastJournalSequence());
    assertEquals(7, result.lastCommitSequence());
    assertEquals(1, result.pageFileCount());
    assertEquals(1, result.pageCount());
    assertEquals(1, result.unrecognizedEntryCount());
    assertEquals(
        ControlFileCodec.RECORD_BYTES
            + WalFileHeaderCodec.HEADER_BYTES
            + WalRecordCodec.HEADER_BYTES + 3
            + PageCodec.PAGE_BYTES,
        result.physicalBytes());
  }

  @Test
  void rejectsWalChecksumDamageWithoutPublishingPartialResult(@TempDir Path root)
      throws IOException {
    writeDatabase(root);
    byte[] wal = Files.readAllBytes(root.resolve("river.wal"));
    wal[wal.length - 1] ^= 0x5a;
    Files.write(root.resolve("river.wal"), wal);

    DatabaseInspectionResult result = new DatabaseInspectionResult();
    assertEquals(
        StatusCode.CORRUPTION,
        new OfflineDatabaseInspector().inspect(root, result));
    assertEquals(false, result.isAvailable());
  }

  @Test
  void rejectsPageFromAnotherDatabase(@TempDir Path root) throws IOException {
    writeDatabase(root);
    writePage(root, DatabaseIncarnation.of(41, 43));

    DatabaseInspectionResult result = new DatabaseInspectionResult();
    assertEquals(
        StatusCode.CORRUPTION,
        new OfflineDatabaseInspector().inspect(root, result));
    assertEquals(false, result.isAvailable());
  }

  @Test
  void rejectsPhysicalNameWhoseGenerationDisagreesWithHeader(@TempDir Path root)
      throws IOException {
    writeDatabase(root);
    Files.move(root.resolve("river.wal"), root.resolve("river.wal.2"));

    DatabaseInspectionResult result = new DatabaseInspectionResult();
    assertEquals(
        StatusCode.CORRUPTION,
        new OfflineDatabaseInspector().inspect(root, result));
    assertEquals(false, result.isAvailable());
  }

  private static void writeDatabase(Path root) throws IOException {
    ByteBuffer control = ByteBuffer.allocate(ControlFileCodec.RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        ControlFileCodec.encode(new ControlFile(DATABASE, GENERATION), control));
    Files.write(root.resolve("river.control"), control.array());

    ByteBuffer header = ByteBuffer.allocate(WalFileHeaderCodec.HEADER_BYTES);
    assertEquals(
        StatusCode.OK,
        WalFileHeaderCodec.encode(new WalFileHeader(DATABASE, GENERATION), header));
    ByteBuffer record = ByteBuffer.allocate(WalRecordCodec.HEADER_BYTES + 3);
    record.put(WalRecordCodec.HEADER_BYTES, (byte) 1);
    record.put(WalRecordCodec.HEADER_BYTES + 1, (byte) 2);
    record.put(WalRecordCodec.HEADER_BYTES + 2, (byte) 3);
    assertEquals(
        StatusCode.OK,
        WalRecordCodec.encodeReserved(1, 5, 7, 1, 1002, 1, 3, record, new CRC32C()));
    byte[] wal = new byte[header.capacity() + record.limit()];
    System.arraycopy(header.array(), 0, wal, 0, header.capacity());
    System.arraycopy(record.array(), 0, wal, header.capacity(), record.limit());
    Files.write(root.resolve("river.wal"), wal);
    writePage(root, DATABASE);
  }

  private static void writePage(Path root, DatabaseIncarnation database) throws IOException {
    ByteBuffer page = ByteBuffer.allocate(PageCodec.PAGE_BYTES);
    page.put(PageCodec.HEADER_BYTES, (byte) 9);
    assertEquals(
        StatusCode.OK,
        PageCodec.encode(
            database,
            GENERATION,
            1,
            1,
            WalFileHeaderCodec.HEADER_BYTES,
            WalFileHeaderCodec.HEADER_BYTES + WalRecordCodec.HEADER_BYTES + 3,
            PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
            PageCodec.SCALAR_OWNER_KEY_ID,
            1,
            page,
            new CRC32C()));
    Files.write(root.resolve("river.indexed.pages"), page.array());
  }
}
