package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class TupleBTreeLeafPageTest {
  private static final long SCHEMA_ID = 41;

  @Test
  void insertsOutOfOrderSeeksPrefixScansAndDeletes() {
    TupleShape shape = shape(new int[] {
        SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT});
    TupleShape prefixShape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer page = page(shape);
    ByteBuffer scratch = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer keys = ByteBuffer.allocate(512);
    int key31 = key(keys, 0, 3, 1, 31);
    int key12 = key(keys, 64, 1, 2, 12);
    int key11 = key(keys, 128, 1, 1, 11);
    int key21 = key(keys, 192, 2, 1, 21);
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insert(
        page, 0, scratch, 0, SCHEMA_ID, shape, keys, 0, key31, workspace));
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insert(
        page, 0, scratch, 0, SCHEMA_ID, shape, keys, 64, key12, workspace));
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insert(
        page, 0, scratch, 0, SCHEMA_ID, shape, keys, 128, key11, workspace));
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.insert(
        page, 0, scratch, 0, SCHEMA_ID, shape, keys, 192, key21, workspace));

    TupleBTreeLookupResult lookup = new TupleBTreeLookupResult();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.lookupExact(
        page, 0, SCHEMA_ID, shape, keys, 64, key12, workspace, lookup));
    assertEquals(12, lookup.logicalRowId());
    assertEquals(StatusCode.CONFLICT, TupleBTreeLeafPage.insert(
        page, 0, scratch, 0, SCHEMA_ID, shape, keys, 64, key12, workspace));

    TupleKeyBuilder prefixBuilder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, prefixBuilder.beginTuple(keys, 320, 1));
    assertEquals(StatusCode.OK, prefixBuilder.addFixed(SqlTypeDescriptor.BIGINT, 1));
    assertEquals(StatusCode.OK, prefixBuilder.finishTuple());
    TupleBTreeRange range = new TupleBTreeRange();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.prefixRange(
        page, 0, SCHEMA_ID, shape,
        keys, 320, prefixBuilder.keyBytes(), prefixShape, workspace, range));
    assertEquals(2, range.count());
    TupleBTreeCursor cursor = new TupleBTreeCursor();
    assertEquals(StatusCode.OK, cursor.open(
        page, 0, workspace.header, range.first(), range.limit()));
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK, cursor.next(entry));
    assertEquals(11, entry.logicalRowId());
    assertEquals(StatusCode.OK, cursor.next(entry));
    assertEquals(12, entry.logicalRowId());
    assertEquals(StatusCode.CONFLICT, cursor.next(entry));

    assertEquals(StatusCode.OK, TupleBTreeLeafPage.delete(
        page, 0, scratch, 0, SCHEMA_ID, shape, keys, 128, key11, workspace));
    assertEquals(StatusCode.CONFLICT, TupleBTreeLeafPage.lookupExact(
        page, 0, SCHEMA_ID, shape, keys, 128, key11, workspace, lookup));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        page, 0, SCHEMA_ID, shape, workspace.header));
  }

  @Test
  void splitsFullVariableKeyPageAndPreservesFenceOrdering() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer source = page(shape);
    ByteBuffer keys = ByteBuffer.allocate(128);
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    int value = 1;
    int length;
    while (true) {
      length = key(keys, 0, value, value);
      StatusCode status = TupleBTreePageCodec.appendLeaf(
          source, 0, shape, keys, 0, length);
      if (status == StatusCode.RESOURCE_EXHAUSTED) break;
      assertEquals(StatusCode.OK, status);
      value++;
    }
    ByteBuffer left = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    TupleBTreeSplitResult split = new TupleBTreeSplitResult();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.splitInsert(
        source, 0, left, 0, right, 0, 16, 17, SCHEMA_ID, shape,
        keys, 0, length, workspace, split));
    assertTrue(split.leftCount() > 0);
    assertTrue(split.rightCount() > 0);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        left, 0, SCHEMA_ID, shape, workspace.header));
    assertEquals(0, workspace.header.leftSiblingPageId());
    assertEquals(17, workspace.header.rightSiblingPageId());
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        right, 0, SCHEMA_ID, shape, workspace.header));
    assertEquals(16, workspace.header.leftSiblingPageId());
    assertEquals(0, workspace.header.rightSiblingPageId());
    TupleBTreeLookupResult lookup = new TupleBTreeLookupResult();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.lookupExact(
        right, 0, SCHEMA_ID, shape, keys, 0, length, workspace, lookup));
    assertEquals(value, lookup.logicalRowId());
  }

  @Test
  void oversizedIndexKeyLeavesPageUnchanged() {
    int text = SqlTypeDescriptor.varchar(255);
    TupleShape shape = shape(new int[] {text, text, text});
    ByteBuffer page = page(shape);
    ByteBuffer scratch = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer key = ByteBuffer.allocate(4_096);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(key, 0, 3));
    assertEquals(StatusCode.OK, builder.addText(text, "a".repeat(255)));
    assertEquals(StatusCode.OK, builder.addText(text, "b".repeat(254)));
    assertEquals(StatusCode.OK, builder.addText(text, "c".repeat(254)));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, builder.finishPhysical(1));
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TupleBTreeLeafPage.insert(
        page, 0, scratch, 0, SCHEMA_ID, shape, key, 0, 3_081, workspace));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        page, 0, SCHEMA_ID, shape, workspace.header));
    assertEquals(0, workspace.header.entryCount());
  }

  private static ByteBuffer page(TupleShape shape) {
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_LEAF, 0,
        shape, SCHEMA_ID, null, 0, 0));
    return page;
  }

  private static int key(
      ByteBuffer target, int offset, long first, long second, long rowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, 2));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, first));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, second));
    assertEquals(StatusCode.OK, builder.finishPhysical(rowId));
    return builder.keyBytes();
  }

  private static int key(ByteBuffer target, int offset, long value, long rowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishPhysical(rowId));
    return builder.keyBytes();
  }

  private static TupleShape shape(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, result));
    return result.value();
  }
}
