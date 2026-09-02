package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class TupleBTreePageCodecTest {
  @Test
  void validates32PartLeafWithoutDuplicatingLogicalRowId() {
    int[] descriptors = new int[32];
    java.util.Arrays.fill(descriptors, SqlTypeDescriptor.BIGINT);
    TupleShape shape = shape(descriptors);
    ByteBuffer keys = ByteBuffer.allocate(1024);
    int firstBytes = key(keys, 0, descriptors, 1, 7);
    int secondBytes = key(keys, 512, descriptors, 2, 8);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_LEAF, 0,
        shape, 11, null, 0, 0));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        page, 0, shape, keys, 0, firstBytes));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        page, 0, shape, keys, 512, secondBytes));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(page, 0, 11, shape, header));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validateEnvelope(page, 0, header));
    assertEquals(32, header.keyArity());
    assertEquals(shape.descriptorHash(), header.descriptorHash());
    TupleBTreeLeafEntry leaf = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.readLeaf(page, 0, header, 1, leaf));
    assertEquals(8, leaf.logicalRowId());
    int slot = TupleBTreePageCodec.HEADER_BYTES;
    assertEquals(0, FormatBytes.getInt(page, slot + 8));
    FormatBytes.putInt(page, slot + 8, 7);
    assertEquals(StatusCode.CORRUPTION,
        TupleBTreePageCodec.validate(page, 0, 11, shape, header));
    assertEquals(StatusCode.CORRUPTION,
        TupleBTreePageCodec.validateEnvelope(page, 0, header));
  }

  @Test
  void descriptorHashRejectsSameArityDifferentShapeAndCorruptSlack() {
    TupleShape shape = shape(new int[] {
        SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(12)});
    TupleShape wrong = shape(new int[] {
        SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(13)});
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_INTERNAL, 3,
        shape, 4, null, 0, 0));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    assertEquals(StatusCode.CORRUPTION,
        TupleBTreePageCodec.validate(page, 0, 4, wrong, header));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validateEnvelope(page, 0, header));
    assertEquals(StatusCode.OK,
        TupleBTreePageCodec.validate(page, 0, 4, shape, header));
    page.put(TupleBTreePageCodec.HEADER_BYTES, (byte) 1);
    assertEquals(StatusCode.CORRUPTION,
        TupleBTreePageCodec.validate(page, 0, 4, shape, header));
  }

  @Test
  void validatesInternalChildrenAndPhysicalFence() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT};
    TupleShape shape = shape(descriptors);
    ByteBuffer keys = ByteBuffer.allocate(128);
    int separator = key(keys, 0, descriptors, 4, 1);
    int high = key(keys, 64, descriptors, 9, 1);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_INTERNAL, 2,
        shape, 5, keys, 64, high));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendInternal(
        page, 0, shape, keys, 0, separator, 3));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(page, 0, 5, shape, header));
    TupleBTreeInternalEntry entry = new TupleBTreeInternalEntry();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.readInternal(page, 0, header, 0, entry));
    assertEquals(3, entry.rightChildPageId());
  }

  private static int key(
      ByteBuffer target, int offset, int[] descriptors, long first, long logicalRowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, descriptors.length));
    for (int index = 0; index < descriptors.length; index++) {
      assertEquals(StatusCode.OK, builder.addFixed(descriptors[index], first + index));
    }
    assertEquals(StatusCode.OK, builder.finishPhysical(logicalRowId));
    return builder.keyBytes();
  }

  private static TupleShape shape(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, result));
    return result.value();
  }
}
