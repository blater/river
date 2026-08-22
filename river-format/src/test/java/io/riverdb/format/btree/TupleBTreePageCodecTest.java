package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class TupleBTreePageCodecTest {
  private static final int FIRST_DESCRIPTOR = SqlTypeDescriptor.BIGINT;
  private static final int SECOND_DESCRIPTOR = SqlTypeDescriptor.BIGINT;
  private static final int THIRD_DESCRIPTOR = SqlTypeDescriptor.varchar(16);
  private static final int FOURTH_DESCRIPTOR = SqlTypeDescriptor.varchar(16);

  @Test
  void validatesInlineCompositeTextKeysAndNonuniqueTieBreaks() {
    ByteBuffer keys = ByteBuffer.allocate(512);
    int firstBytes = key(keys, 0, "BAR", "ALICE", 7);
    int secondBytes = key(keys, 128, "BAR", "ALICE", 8);
    int highBytes = key(keys, 256, "CAR", "BOB", 9);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES + 8);
    assertEquals(
        StatusCode.OK,
        TupleBTreePageCodec.initialize(
            page,
            8,
            TupleBTreePageCodec.TYPE_LEAF,
            2,
            4,
            FIRST_DESCRIPTOR,
            SECOND_DESCRIPTOR,
            THIRD_DESCRIPTOR,
            FOURTH_DESCRIPTOR,
            11,
            keys,
            256,
            highBytes));
    assertEquals(
        StatusCode.OK,
        TupleBTreePageCodec.appendLeaf(page, 8, keys, 0, firstBytes, 7));
    assertEquals(
        StatusCode.OK,
        TupleBTreePageCodec.appendLeaf(page, 8, keys, 128, secondBytes, 8));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, validate(page, 8, 11, header));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "5054425554564952010000000100000002000000100000000200000070000000"
                + "a43e00003c3f0000440000000400000001000000010000000210000002100000"
                + "0b000000000000000000000000000000f03e00004c0000000700000000000000"
                + "a43e00004c0000000800000000000000"),
        Arrays.copyOfRange(page.array(), 8, 120));
    assertEquals(2, header.entryCount());
    assertEquals(4, header.keyArity());
    assertEquals(THIRD_DESCRIPTOR, header.descriptor(2));
    assertEquals(11, header.keySchemaId());
    TupleBTreeLeafEntry leaf = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.readLeaf(page, 8, header, 0, leaf));
    assertEquals(7, leaf.logicalRowId());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readInternal(
            page, 8, header, 0, new TupleBTreeInternalEntry()));
    assertEquals(StatusCode.CORRUPTION, validate(page, 8, 12, header));
    assertEquals(StatusCode.OK, validate(page, 8, 11, header));

    assertEquals(
        StatusCode.CONFLICT,
        TupleBTreePageCodec.appendLeaf(page, 8, keys, 0, firstBytes, 7));
    int firstSlot = 8 + TupleBTreePageCodec.HEADER_BYTES;
    int secondSlot = firstSlot + TupleBTreePageCodec.SLOT_BYTES;
    int secondOffset = FormatBytes.getInt(page, secondSlot);
    FormatBytes.putInt(page, secondSlot, FormatBytes.getInt(page, firstSlot));
    assertEquals(StatusCode.CORRUPTION, validate(page, 8, 11, header));
    FormatBytes.putInt(page, secondSlot, secondOffset);
    FormatBytes.putLong(page, 8 + TupleBTreePageCodec.HEADER_BYTES + 8, 9);
    assertEquals(StatusCode.CORRUPTION, validate(page, 8, 11, header));
  }

  @Test
  void validatesInternalChildrenShapeFenceAndZeroSlack() {
    ByteBuffer keys = ByteBuffer.allocate(384);
    int firstBytes = key(keys, 0, "BAR", "ALICE", 7);
    int secondBytes = key(keys, 128, "CAR", "BOB", 8);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(
        StatusCode.OK,
        TupleBTreePageCodec.initialize(
            page,
            0,
            TupleBTreePageCodec.TYPE_INTERNAL,
            1,
            4,
            FIRST_DESCRIPTOR,
            SECOND_DESCRIPTOR,
            THIRD_DESCRIPTOR,
            FOURTH_DESCRIPTOR,
            12,
            null,
            0,
            0));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, validate(page, 0, 12, header));
    assertEquals(
        StatusCode.OK,
        TupleBTreePageCodec.appendInternal(page, 0, keys, 0, firstBytes, 2));
    assertEquals(
        StatusCode.OK,
        TupleBTreePageCodec.appendInternal(page, 0, keys, 128, secondBytes, 3));
    assertEquals(StatusCode.OK, validate(page, 0, 12, header));
    TupleBTreeInternalEntry internal = new TupleBTreeInternalEntry();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.readInternal(page, 0, header, 1, internal));
    assertEquals(3, internal.rightChildPageId());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.readLeaf(page, 0, header, 0, new TupleBTreeLeafEntry()));

    int freeStart = FormatBytes.getInt(page, 28);
    page.put(freeStart, (byte) 1);
    assertEquals(StatusCode.CORRUPTION, validate(page, 0, 12, header));
    page.put(freeStart, (byte) 0);
    FormatBytes.putInt(page, TupleBTreePageCodec.HEADER_BYTES + 12, 1);
    assertEquals(StatusCode.CORRUPTION, validate(page, 0, 12, header));
  }

  @Test
  void rejectsIncoherentLeafFencesAndWrongKeyShape() {
    ByteBuffer key = ByteBuffer.allocate(128);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.begin(key, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.TYPE_ID_BIGINT, 1));
    assertEquals(StatusCode.OK, builder.finish(1));
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.initialize(
            page, 0, TupleBTreePageCodec.TYPE_LEAF, 0, 0,
            0, 0, 0, 0, 1, null, 0, 0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.initialize(
            page, 0, TupleBTreePageCodec.TYPE_LEAF, 0, 1,
            SqlTypeDescriptor.BIGINT, 0, 0, 0, 1, key, 0, builder.keyBytes()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.initialize(
            page, 0, TupleBTreePageCodec.TYPE_LEAF, 1, 1,
            SqlTypeDescriptor.BIGINT, 0, 0, 0, 1, null, 0, 0));

    assertEquals(
        StatusCode.OK,
        TupleBTreePageCodec.initialize(
            page, 0, TupleBTreePageCodec.TYPE_LEAF, 0, 4,
            FIRST_DESCRIPTOR, SECOND_DESCRIPTOR, THIRD_DESCRIPTOR, FOURTH_DESCRIPTOR,
            1, null, 0, 0));
    FormatBytes.putInt(page, 44, 0);
    FormatBytes.putInt(page, 48, 0);
    assertEquals(StatusCode.CORRUPTION, validate(page, 0, 1, new TupleBTreePageHeader()));
    assertEquals(
        StatusCode.OK,
        TupleBTreePageCodec.initialize(
            page, 0, TupleBTreePageCodec.TYPE_LEAF, 0, 4,
            FIRST_DESCRIPTOR, SECOND_DESCRIPTOR, THIRD_DESCRIPTOR, FOURTH_DESCRIPTOR,
            1, null, 0, 0));
    FormatBytes.putInt(page, 8, 0);
    assertEquals(StatusCode.CORRUPTION, validate(page, 0, 1, new TupleBTreePageHeader()));
    assertEquals(
        StatusCode.OK,
        TupleBTreePageCodec.initialize(
            page, 0, TupleBTreePageCodec.TYPE_LEAF, 0, 4,
            FIRST_DESCRIPTOR, SECOND_DESCRIPTOR, THIRD_DESCRIPTOR, FOURTH_DESCRIPTOR,
            1, null, 0, 0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        TupleBTreePageCodec.appendLeaf(page, 0, key, 0, builder.keyBytes(), 1));
  }

  private static int key(
      ByteBuffer target, int offset, String last, String first, long logicalRowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.begin(target, offset, 4));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.TYPE_ID_BIGINT, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.TYPE_ID_BIGINT, 2));
    assertEquals(StatusCode.OK, builder.addText(THIRD_DESCRIPTOR, last));
    assertEquals(StatusCode.OK, builder.addText(FOURTH_DESCRIPTOR, first));
    assertEquals(StatusCode.OK, builder.finish(logicalRowId));
    return builder.keyBytes();
  }

  private static StatusCode validate(
      ByteBuffer page, int start, long schemaId, TupleBTreePageHeader header) {
    return TupleBTreePageCodec.validate(
        page,
        start,
        schemaId,
        4,
        FIRST_DESCRIPTOR,
        SECOND_DESCRIPTOR,
        THIRD_DESCRIPTOR,
        FOURTH_DESCRIPTOR,
        header);
  }
}
