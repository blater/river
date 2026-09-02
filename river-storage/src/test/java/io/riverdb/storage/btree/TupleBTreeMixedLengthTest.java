package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** Skewed encoded-size coverage for byte-balanced leaf and internal splits. */
final class TupleBTreeMixedLengthTest {
  private static final long SCHEMA_ID = 91;
  private static final int TEXT = SqlTypeDescriptor.varchar(250);

  @Test
  void growsAndSearchesTreeWithMixedNearMaximumKeys() {
    TupleShape shape = shape();
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(512);
    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, shape);
    TupleBTreeTreeWorkspace workspace = workspace();
    ByteBuffer key = ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES);
    TupleBTreeInsertPreflightResult preflight = new TupleBTreeInsertPreflightResult();
    int maximumChangedPages = 0;
    assertEquals(StatusCode.OK, tree.initialize(workspace));
    for (int value = 1; value <= 240; value++) {
      int length = key(key, value);
      assertEquals(StatusCode.OK, tree.preflightInsert(
          key, 0, length, workspace, preflight));
      assertFalse(preflight.keyExists());
      maximumChangedPages = Math.max(maximumChangedPages, preflight.changedPageCount());
      assertEquals(StatusCode.OK, tree.insert(key, 0, length, workspace));
    }

    int length = key(key, 175);
    TupleBTreeLookupResult lookup = new TupleBTreeLookupResult();
    assertEquals(StatusCode.OK, tree.lookupExact(key, 0, length, workspace, lookup));
    assertEquals(175, lookup.logicalRowId());
    TupleBTreeValidationResult validation = new TupleBTreeValidationResult();
    assertEquals(StatusCode.OK, tree.validate(workspace, validation));
    assertEquals(240, validation.entryCount());
    assertTrue(validation.height() >= 3);
    assertTrue(maximumChangedPages >= 5);
  }

  private static int key(ByteBuffer target, int value) {
    int length = switch (value % 5) {
      case 0 -> 240;
      case 1 -> 1;
      case 2 -> 96;
      case 3 -> 220;
      default -> 12;
    };
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, 0, 4));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.addText(TEXT, "a".repeat(length)));
    assertEquals(StatusCode.OK, builder.addText(TEXT, "b".repeat(length)));
    assertEquals(StatusCode.OK, builder.addText(TEXT, "c".repeat(length)));
    assertEquals(StatusCode.OK, builder.finishPhysical(value));
    return builder.keyBytes();
  }

  private static TupleBTreeTreeWorkspace workspace() {
    return new TupleBTreeTreeWorkspace(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES),
        ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES),
        new int[TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT],
        new int[TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT],
        new int[TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT]);
  }

  private static TupleShape shape() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(
        new int[] {SqlTypeDescriptor.BIGINT, TEXT, TEXT, TEXT}, result));
    assertTrue(result.value().maximumEncodedBytes()
        <= TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES);
    return result.value();
  }
}
