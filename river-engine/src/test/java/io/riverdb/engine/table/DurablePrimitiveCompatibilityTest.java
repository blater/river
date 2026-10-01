package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.format.wal.WalRecordHeader;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class DurablePrimitiveCompatibilityTest {
  @Test
  void codecsKeepIndependentLittleEndianBytesOnUnalignedDirectSlices() {
    long identity = -0x0102030405060708L;
    ByteBuffer page = slice(PageCodec.PAGE_BYTES + 3);
    assertEquals(StatusCode.OK, PageCodec.encodeAt(
        DatabaseIncarnation.of(identity, Long.MIN_VALUE), WalGeneration.of(1),
        0x01020304L, 1, 0, 0, PageCodec.PAYLOAD_KIND_SCALAR_BTREE, 0, 0,
        page, 3, new CRC32C()));
    ByteBuffer little = page.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x5249564552504147L, little.getLong(3));
    assertEquals(identity, little.getLong(27));
    assertEquals(Long.MIN_VALUE, little.getLong(35));
    assertEquals(0x01020304L, little.getLong(51));
    assertEquals(StatusCode.OK, PageCodec.validateAt(
        page.asReadOnlyBuffer(), 3, new PageHeader(), new CRC32C()));

    ByteBuffer wal = slice(WalRecordCodec.HEADER_BYTES);
    assertEquals(StatusCode.OK, WalRecordCodec.encodeReserved(
        1, identity, Long.MAX_VALUE, 0, 0x01020304, 1, 0, wal, new CRC32C()));
    little = wal.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x524956455257414cL, little.getLong(0));
    assertEquals(0x01020304, little.getInt(24));
    assertEquals(identity, little.getLong(40));
    assertEquals(Long.MAX_VALUE, little.getLong(48));
    assertEquals(StatusCode.OK, WalRecordCodec.validate(
        wal.asReadOnlyBuffer(), new WalRecordHeader(), new CRC32C()));

    ByteBuffer heap = slice(256);
    assertEquals(StatusCode.OK, HeapPage.initialize(heap));
    little = heap.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x5249564552484550L, little.getLong(0));
    assertEquals(256, little.getInt(20));
    assertEquals(StatusCode.OK, HeapPage.validate(heap.asReadOnlyBuffer()));

    ByteBuffer indexed = slice(IndexedWalCodec.pageOperationBytes(1, 0));
    IndexedWalCodec.encodePageOperationHeader(indexed, 1, 0);
    little = indexed.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x5249564552494458L, little.getLong(0));
    assertEquals(7, little.getInt(8));
    assertEquals(1, little.getInt(16));
    assertEquals(StatusCode.OK, IndexedWalCodec.validatePageOperation(
        indexed.asReadOnlyBuffer(), 1));
  }

  private static ByteBuffer slice(int bytes) {
    ByteBuffer container = ByteBuffer.allocateDirect(bytes + 1);
    container.position(1);
    return container.slice().order(ByteOrder.BIG_ENDIAN);
  }
}
