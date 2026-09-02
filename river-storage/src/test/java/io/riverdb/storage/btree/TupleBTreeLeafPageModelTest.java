package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class TupleBTreeLeafPageModelTest {
  @Test
  void randomizedMutationsMatchFiniteReferenceModel() {
    TupleShape shape = shape();
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer scratch = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_LEAF, 0,
        shape, 73, null, 0, 0));
    ByteBuffer key = ByteBuffer.allocate(64);
    boolean[][] present = new boolean[40][4];
    Random random = new Random(0x51a7c0deL);
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    TupleBTreeLookupResult lookup = new TupleBTreeLookupResult();
    for (int operation = 0; operation < 1_000; operation++) {
      int value = random.nextInt(present.length);
      int row = random.nextInt(present[value].length);
      int length = key(key, value, row + 1L);
      boolean insert = random.nextBoolean();
      StatusCode expected = present[value][row] == insert
          ? StatusCode.CONFLICT : StatusCode.OK;
      StatusCode actual = insert
          ? TupleBTreeLeafPage.insert(
              page, 0, scratch, 0, 73, shape, key, 0, length, workspace)
          : TupleBTreeLeafPage.delete(
              page, 0, scratch, 0, 73, shape, key, 0, length, workspace);
      assertEquals(expected, actual);
      if (actual.isOk()) present[value][row] = insert;
      if (operation % 25 == 0) {
        assertModel(page, key, present, shape, workspace, lookup);
      }
    }
    assertModel(page, key, present, shape, workspace, lookup);
  }

  private static void assertModel(
      ByteBuffer page, ByteBuffer key, boolean[][] present, TupleShape shape,
      TupleBTreeWorkspace workspace, TupleBTreeLookupResult lookup) {
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validate(
        page, 0, 73, shape, workspace.header));
    int expectedCount = 0;
    for (boolean[] rows : present) {
      for (boolean value : rows) if (value) expectedCount++;
    }
    assertEquals(expectedCount, workspace.header.entryCount());
    for (int value = 0; value < present.length; value++) {
      for (int row = 0; row < present[value].length; row++) {
        int length = key(key, value, row + 1L);
        StatusCode expected = present[value][row] ? StatusCode.OK : StatusCode.CONFLICT;
        assertEquals(expected, TupleBTreeLeafPage.lookupExact(
            page, 0, 73, shape, key, 0, length, workspace, lookup));
      }
    }
    TupleBTreeLeafEntry previous = new TupleBTreeLeafEntry();
    TupleBTreeLeafEntry current = new TupleBTreeLeafEntry();
    for (int index = 0; index < expectedCount; index++) {
      assertEquals(StatusCode.OK, TupleBTreePageCodec.readLeaf(
          page, 0, workspace.header, index, current));
      if (index > 0) {
        assertTrue(TupleKeyCodec.compare(
            page, previous.keyOffset(), previous.keyLength(),
            page, current.keyOffset(), current.keyLength()) < 0);
      }
      TupleBTreeLeafEntry swap = previous;
      previous = current;
      current = swap;
    }
  }

  private static int key(ByteBuffer target, long value, long rowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishPhysical(rowId));
    return builder.keyBytes();
  }

  private static TupleShape shape() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(
        new int[] {SqlTypeDescriptor.BIGINT}, result));
    return result.value();
  }
}
