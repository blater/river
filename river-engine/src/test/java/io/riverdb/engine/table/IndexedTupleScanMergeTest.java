package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleBTreePageHeader;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.TupleBTreeCursor;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IndexedTupleScanMergeTest {
  @Test
  void committedResultBorrowsTheSameLeafValueAndModificationSequence() {
    TupleShape.Result shapeResult = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(
        new int[] {SqlTypeDescriptor.BIGINT}, shapeResult));
    TupleShape shape = shapeResult.value();
    ByteBuffer key = ByteBuffer.allocate(64);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(key, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, 4));
    assertEquals(StatusCode.OK, builder.finishPhysical(31));
    ByteBuffer value = ByteBuffer.allocate(16);
    value.put(0, (byte) 79);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 0, shape, 7, null, 0, 0));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        page, 0, shape, key, 0, builder.keyBytes(),
        value, 0, value.capacity(), 0, 0, 93));
    TupleBTreePageHeader header = new TupleBTreePageHeader();
    assertEquals(StatusCode.OK, TupleBTreePageCodec.validateForRead(
        page, 0, 7, shape, header, new TupleBTreePageValidationProof()));
    TupleBTreeCursor cursor = new TupleBTreeCursor();
    assertEquals(StatusCode.OK, cursor.open(page, 0, header, 0, 1));
    TupleBTreeScanBounds bounds = new TupleBTreeScanBounds();
    assertEquals(StatusCode.OK, bounds.setAll(TupleBTreeScanBounds.FORWARD));
    IndexedTupleScanMerge merge = new IndexedTupleScanMerge();
    IndexedTupleIntentJournal intents = new IndexedTupleIntentJournal();
    merge.prepare(intents, 3, bounds);
    IndexedTupleScanResult result = new IndexedTupleScanResult();
    assertEquals(StatusCode.OK, merge.next(
        cursor, new TupleBTreeLeafEntry(), intents, result));
    assertTrue(result.committed());
    assertSame(page, result.page());
    assertEquals(31, result.logicalRowId());
    assertEquals(93, result.modificationSequence());
    assertEquals(16, result.valueLength());
    assertEquals((byte) 79, result.page().get(result.valueOffset()));
    assertEquals(StatusCode.OK, cursor.close());
  }
}
