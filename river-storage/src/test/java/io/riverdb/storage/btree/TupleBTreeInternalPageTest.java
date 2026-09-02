package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class TupleBTreeInternalPageTest {
  @Test
  void routesAcrossInsertedSeparatorsAndSplitsWithPromotedKey() {
    TupleShape shape = shape();
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_INTERNAL, 1,
        shape, 9, null, 0, 0));
    ByteBuffer scratch = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer keys = ByteBuffer.allocate(256);
    int key20 = key(keys, 0, 20);
    int key10 = key(keys, 64, 10);
    int key30 = key(keys, 128, 30);
    int key5 = key(keys, 192, 5);
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.OK, TupleBTreeInternalPage.insert(
        page, 0, scratch, 0, 9, shape, keys, 0, key20, 3, workspace));
    assertEquals(StatusCode.OK, TupleBTreeInternalPage.insert(
        page, 0, scratch, 0, 9, shape, keys, 64, key10, 2, workspace));
    assertEquals(1, TupleBTreeInternalPage.childForKey(
        page, 0, 9, shape, keys, 192, key5, workspace));
    assertEquals(2, TupleBTreeInternalPage.childForKey(
        page, 0, 9, shape, keys, 64, key10, workspace));
    assertEquals(3, TupleBTreeInternalPage.childForKey(
        page, 0, 9, shape, keys, 0, key20, workspace));

    ByteBuffer left = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    TupleBTreeSplitResult split = new TupleBTreeSplitResult();
    assertEquals(StatusCode.OK, TupleBTreeInternalPage.splitInsert(
        page, 0, left, 0, right, 0, 9, shape,
        keys, 128, key30, 4, workspace, split));
    assertEquals(1, split.leftCount());
    assertEquals(1, split.rightCount());
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        left, 0, 9, shape, workspace.header));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        right, 0, 9, shape, workspace.header));
  }

  private static int key(ByteBuffer target, int offset, long value) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishPhysical(1));
    return builder.keyBytes();
  }

  private static TupleShape shape() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(
        new int[] {SqlTypeDescriptor.BIGINT}, result));
    return result.value();
  }
}
