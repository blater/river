package io.riverdb.format.page;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class LogicalHeadPageCodecTest {
  @Test
  void storesSparseTableRootsAndRowHeadsAtBothEndsOfTheirPages() {
    ByteBuffer table = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES)
        .order(ByteOrder.BIG_ENDIAN);
    assertEquals(StatusCode.OK, LogicalHeadPageCodec.initialize(
        table, LogicalHeadPageCodec.TABLE_LEAF, 0, 8, 0));
    LogicalHeadPageCodec.tableRoot(
        table, 0, 19, 0);
    int lastTableSlot = LogicalHeadPageCodec.TABLE_LEAF_ENTRIES - 1;
    LogicalHeadPageCodec.tableRoot(
        table, lastTableSlot, 41, 2);
    assertEquals(StatusCode.OK, LogicalHeadPageCodec.validate(table));
    assertEquals(19, LogicalHeadPageCodec.tableRootPageId(table, 0));
    assertEquals(41, LogicalHeadPageCodec.tableRootPageId(table, lastTableSlot));
    assertEquals(2, LogicalHeadPageCodec.tableRootLevel(table, lastTableSlot));

    ByteBuffer row = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES)
        .order(ByteOrder.BIG_ENDIAN);
    assertEquals(StatusCode.OK, LogicalHeadPageCodec.initialize(
        row, LogicalHeadPageCodec.ROW_LEAF, 17, 2, 0));
    int lastRowSlot = LogicalHeadPageCodec.ROW_LEAF_ENTRIES - 1;
    LogicalHeadPageCodec.rowHead(row, 0, 73);
    LogicalHeadPageCodec.rowHead(row, lastRowSlot, 97);
    assertEquals(StatusCode.OK, LogicalHeadPageCodec.validate(row));
    assertEquals(73, LogicalHeadPageCodec.rowHead(row, 0));
    assertEquals(97, LogicalHeadPageCodec.rowHead(row, lastRowSlot));
  }

  @Test
  void rejectsMalformedIdentityAndKeepsThePageEnvelopeOwnedByItsTable() {
    ByteBuffer payload = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, LogicalHeadPageCodec.initialize(
        payload, LogicalHeadPageCodec.ROW_LEAF, 0, 0, 0));
    assertEquals(StatusCode.OK, LogicalHeadPageCodec.initialize(
        payload, LogicalHeadPageCodec.ROW_BRANCH, 7, 0, 1));
    LogicalHeadPageCodec.branchChild(payload, LogicalHeadPageCodec.BRANCH_ENTRIES - 1, 27);
    assertEquals(27, LogicalHeadPageCodec.branchChild(
        payload, LogicalHeadPageCodec.BRANCH_ENTRIES - 1));
    assertEquals(StatusCode.OK, LogicalHeadPageCodec.validate(payload));
    FormatBytes.putInt(payload, 36, 27);
    assertEquals(StatusCode.CORRUPTION, LogicalHeadPageCodec.validate(payload));
    FormatBytes.putInt(payload, 36, 0);
    FormatBytes.putLong(payload, 16, 0);
    assertEquals(StatusCode.CORRUPTION, LogicalHeadPageCodec.validate(payload));

    ByteBuffer page = ByteBuffer.allocate(PageCodec.PAGE_BYTES);
    assertEquals(StatusCode.OK, PageCodec.encode(
        DatabaseIncarnation.of(1, 2), WalGeneration.of(1), 4, 1, 0, 0,
        PageCodec.PAYLOAD_KIND_LOGICAL_HEAD, 7, PageCodec.MAX_PAYLOAD_BYTES,
        page, new CRC32C()));
    PageHeader header = new PageHeader();
    assertEquals(StatusCode.OK, PageCodec.validate(page, header, new CRC32C()));
    assertEquals(7, header.ownerKeyId());
    assertEquals(PageCodec.PAYLOAD_KIND_LOGICAL_HEAD, header.payloadKind());
  }
}
