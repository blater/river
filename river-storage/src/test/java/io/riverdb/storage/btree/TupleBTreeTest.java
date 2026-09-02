package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class TupleBTreeTest {
  private static final long SCHEMA_ID = 73;
  private static final int TEXT = SqlTypeDescriptor.varchar(255);
  private static final String PREFIX = "p".repeat(255);

  @Test
  void validatesAnUnchangedPageOnceAndInvalidatesOnGenerationOrSchemaChange() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(8);
    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, shape);
    TupleBTreeTreeWorkspace workspace = workspace();
    assertEquals(StatusCode.OK, tree.initialize(workspace));
    ByteBuffer key = ByteBuffer.allocate(128);
    int keyLength = bigintKey(key, 0, 7);
    assertEquals(StatusCode.OK, tree.insert(key, 0, keyLength, workspace));

    TupleBTreeLookupResult result = new TupleBTreeLookupResult();
    int validationsBeforeLookup = pages.validationCount();
    int missesBeforeLookup = pages.validationMissCount();
    assertEquals(StatusCode.OK, tree.lookupExact(
        key, 0, keyLength, workspace, result));
    assertEquals(validationsBeforeLookup + 1, pages.validationCount());
    assertEquals(missesBeforeLookup + 1, pages.validationMissCount());
    for (int attempt = 0; attempt < 4; attempt++) {
      assertEquals(StatusCode.OK, tree.lookupExact(
          key, 0, keyLength, workspace, result));
    }
    assertEquals(validationsBeforeLookup + 1, pages.validationCount());
    assertEquals(missesBeforeLookup + 1, pages.validationMissCount());

    pages.bumpPageGeneration(pages.rootPageId());
    assertEquals(StatusCode.OK, tree.lookupExact(
        key, 0, keyLength, workspace, result));
    assertEquals(validationsBeforeLookup + 2, pages.validationCount());
    assertEquals(missesBeforeLookup + 2, pages.validationMissCount());

    assertEquals(StatusCode.OK, tree.configure(pages, SCHEMA_ID + 1, shape));
    assertEquals(StatusCode.CORRUPTION, tree.lookupExact(
        key, 0, keyLength, workspace, result));
    assertEquals(validationsBeforeLookup + 2, pages.validationCount());
    assertEquals(missesBeforeLookup + 3, pages.validationMissCount());
    assertEquals(StatusCode.OK, tree.configure(pages, SCHEMA_ID, shape));
    assertEquals(StatusCode.OK, tree.lookupExact(
        key, 0, keyLength, workspace, result));
    assertEquals(validationsBeforeLookup + 2, pages.validationCount());
  }

  @Test
  void updatesApproximateCompositeKeysWithoutBreakingTreeValidation() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.REAL, SqlTypeDescriptor.DOUBLE});
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(8);
    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, shape);
    TupleBTreeTreeWorkspace workspace = workspace();
    assertEquals(StatusCode.OK, tree.initialize(workspace));
    ByteBuffer keys = ByteBuffer.allocate(256);
    int first = approximateKey(keys, 0, 1.25f, -2.5d, 1);
    int second = approximateKey(keys, 64, 0.0f, 9.75d, 2);
    int updated = approximateKey(keys, 128, 3.5f, -2.5d, 1);
    assertEquals(StatusCode.OK, tree.insert(keys, 0, first, workspace));
    assertEquals(StatusCode.OK, tree.insert(keys, 64, second, workspace));
    assertEquals(StatusCode.OK, tree.delete(keys, 0, first, workspace));
    assertEquals(StatusCode.OK, tree.insert(keys, 128, updated, workspace));
    TupleBTreeValidationResult validation = new TupleBTreeValidationResult();
    assertEquals(StatusCode.OK, tree.validate(workspace, validation));
    assertEquals(2, validation.entryCount());
  }

  @Test
  void configureRejectsOversizedShapeWithoutChangingIdentity() {
    TupleShape original = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleShape oversized = shape(new int[] {
        TEXT, TEXT, TEXT, TEXT});
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(4);
    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, original);

    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        tree.configure(pages, SCHEMA_ID + 1, oversized));
    assertEquals(SCHEMA_ID, tree.schemaId());
    assertEquals(original, tree.shape());
    assertEquals(pages, tree.provider());
  }

  @Test
  void growsInternalRootAndTraversesPrefixAndRangeAcrossLeaves() {
    TupleShape shape = shape(new int[] {TEXT, SqlTypeDescriptor.BIGINT});
    TupleShape prefixShape = shape(new int[] {TEXT});
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(128);
    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, shape);
    TupleBTreeTreeWorkspace workspace = workspace();
    assertEquals(StatusCode.OK, tree.initialize(workspace));
    ByteBuffer key = ByteBuffer.allocate(2_048);
    for (int value = 0; value < 400; value++) {
      int length = key(key, 0, value, value + 1);
      assertEquals(StatusCode.OK, tree.insert(key, 0, length, workspace));
    }

    int soughtLength = key(key, 0, 250, 251);
    TupleBTreeLookupResult lookup = new TupleBTreeLookupResult();
    assertEquals(StatusCode.OK, tree.lookupExact(key, 0, soughtLength, workspace, lookup));
    assertTrue(lookup.pageId() > 0);
    assertEquals(251, lookup.logicalRowId());

    ByteBuffer prefix = ByteBuffer.allocate(1_100);
    int prefixLength = prefix(prefix);
    TupleBTreeCursor cursor = new TupleBTreeCursor();
    assertEquals(StatusCode.OK, cursor.openPrefix(
        tree, prefix, 0, prefixLength, prefixShape, workspace));
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    int rows = 0;
    while (cursor.next(entry).isOk()) rows++;
    assertEquals(400, rows);

    ByteBuffer bounds = ByteBuffer.allocate(4_096);
    int lowerLength = userKey(bounds, 0, 100);
    int upperLength = userKey(bounds, 2_048, 200);
    TupleBTreeScanBounds scan = new TupleBTreeScanBounds();
    assertEquals(StatusCode.OK, scan.setRange(
        bounds, 0, lowerLength, shape, true,
        bounds, 2_048, upperLength, shape, false, TupleBTreeScanBounds.FORWARD));
    assertEquals(StatusCode.OK, cursor.open(tree, scan, workspace));
    for (int index = 0; index < bounds.limit(); index++) bounds.put(index, (byte) 0);
    rows = 0;
    while (cursor.next(entry).isOk()) {
      assertEquals(101 + rows, entry.logicalRowId());
      rows++;
    }
    assertEquals(100, rows);

    lowerLength = userKey(bounds, 0, 100);
    upperLength = userKey(bounds, 2_048, 200);
    assertEquals(StatusCode.OK, scan.setRange(
        bounds, 0, lowerLength, shape, true,
        bounds, 2_048, upperLength, shape, false, TupleBTreeScanBounds.REVERSE));
    assertEquals(StatusCode.OK, cursor.open(tree, scan, workspace));
    rows = 0;
    while (cursor.next(entry).isOk()) {
      assertEquals(200 - rows, entry.logicalRowId());
      rows++;
    }
    assertEquals(100, rows);

    int exactLength = userKey(bounds, 0, 250);
    assertEquals(StatusCode.OK, scan.setExact(
        bounds, 0, exactLength, shape, TupleBTreeScanBounds.REVERSE));
    assertEquals(StatusCode.OK, cursor.open(tree, scan, workspace));
    assertEquals(StatusCode.OK, cursor.next(entry));
    assertEquals(251, entry.logicalRowId());
    assertEquals(StatusCode.CONFLICT, cursor.next(entry));

    assertEquals(StatusCode.OK, scan.setAll(TupleBTreeScanBounds.REVERSE));
    assertEquals(StatusCode.OK, cursor.open(tree, scan, workspace));
    rows = 0;
    while (cursor.next(entry).isOk()) {
      assertEquals(400 - rows, entry.logicalRowId());
      rows++;
    }
    assertEquals(400, rows);

    assertEquals(StatusCode.OK, tree.delete(key, 0, soughtLength, workspace));
    assertEquals(StatusCode.CONFLICT,
        tree.lookupExact(key, 0, soughtLength, workspace, lookup));
    TupleBTreeValidationResult validation = new TupleBTreeValidationResult();
    assertEquals(StatusCode.OK, tree.validate(workspace, validation));
    assertEquals(399, validation.entryCount());
    assertTrue(validation.height() >= 3);
    assertTrue(validation.leafCount() > 1);

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, tree.validate(null, validation));
    assertEquals(0, validation.height());
    assertEquals(0, validation.pageCount());
    assertEquals(0, validation.leafCount());
    assertEquals(0, validation.entryCount());
    assertEquals(StatusCode.OK, tree.validate(workspace, validation));

    corruptFirstLeafLink(pages);
    assertEquals(StatusCode.CORRUPTION, tree.validate(workspace, validation));
  }

  @Test
  void rejectsAPathBeyondTheCheckedMaximumHeight() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(40);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    for (int pageId = 1; pageId <= TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT; pageId++) {
      assertEquals(StatusCode.OK, pages.allocate(reference));
      assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
          reference.page(), reference.start(), TupleBTreePageCodec.TYPE_INTERNAL,
          pageId + 1, shape, SCHEMA_ID, null, 0, 0));
      assertEquals(StatusCode.OK, pages.release(reference));
      reference.reset();
    }
    assertEquals(StatusCode.OK, pages.allocate(reference));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        reference.page(), reference.start(), TupleBTreePageCodec.TYPE_LEAF,
        0, shape, SCHEMA_ID, null, 0, 0));
    assertEquals(StatusCode.OK, pages.release(reference));
    reference.reset();
    pages.setRootPageId(1);
    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, shape);
    ByteBuffer key = ByteBuffer.allocate(64);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(key, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, 1));
    assertEquals(StatusCode.OK, builder.finishPhysical(1));
    assertEquals(StatusCode.CORRUPTION, tree.lookupExact(
        key, 0, builder.keyBytes(), workspace(), new TupleBTreeLookupResult()));
  }

  @Test
  void rejectsLeavesAtDifferentDepths() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(5);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    for (int pageId = 1; pageId <= 5; pageId++) {
      assertEquals(StatusCode.OK, pages.allocate(reference));
      assertEquals(StatusCode.OK, pages.release(reference));
      reference.reset();
    }
    ByteBuffer keys = ByteBuffer.allocate(192);
    int first = bigintKey(keys, 0, 10);
    int rootSeparator = bigintKey(keys, 64, 50);
    int lowerSeparator = bigintKey(keys, 128, 75);
    initializeLeaf(pages.page(2), shape, 0, 4,
        keys, 64, rootSeparator, keys, 0, first);
    initializeLeaf(pages.page(4), shape, 2, 5,
        keys, 128, lowerSeparator, keys, 64, rootSeparator);
    initializeLeaf(pages.page(5), shape, 4, 0,
        null, 0, 0, keys, 128, lowerSeparator);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        pages.page(3), 0, TupleBTreePageCodec.TYPE_INTERNAL, 4,
        shape, SCHEMA_ID, null, 0, 0));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendInternal(
        pages.page(3), 0, shape, keys, 128, lowerSeparator, 5));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        pages.page(1), 0, TupleBTreePageCodec.TYPE_INTERNAL, 2,
        shape, SCHEMA_ID, null, 0, 0));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendInternal(
        pages.page(1), 0, shape, keys, 64, rootSeparator, 3));
    pages.setRootPageId(1);

    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, shape);
    assertEquals(StatusCode.CORRUPTION,
        tree.validate(workspace(), new TupleBTreeValidationResult()));
  }

  @Test
  void retainsFailedReleasesForExplicitRecoveryAndMutationAbort() {
    TupleShape shape = shape(new int[] {TEXT, SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(32);
    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, shape);
    TupleBTreeTreeWorkspace workspace = workspace();
    ByteBuffer key = ByteBuffer.allocate(2_048);
    assertEquals(StatusCode.OK, tree.initialize(workspace));
    int keyLength = key(key, 0, 0, 1);
    assertEquals(StatusCode.OK, tree.insert(key, 0, keyLength, workspace));

    pages.failNextRelease();
    assertEquals(StatusCode.IO_FAILURE, tree.lookupExact(
        key, 0, keyLength, workspace, new TupleBTreeLookupResult()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, tree.lookupExact(
        key, 0, keyLength, workspace, new TupleBTreeLookupResult()));
    assertEquals(StatusCode.OK, workspace.releaseRetained(pages));
    assertEquals(StatusCode.OK, tree.lookupExact(
        key, 0, keyLength, workspace, new TupleBTreeLookupResult()));

    int value = 1;
    while (!wouldSplit(pages, shape, key, value)) {
      keyLength = key(key, 0, value, value + 1L);
      assertEquals(StatusCode.OK, tree.insert(key, 0, keyLength, workspace));
      value++;
    }
    keyLength = key(key, 0, value, value + 1L);
    pages.failReleaseAfter(1);
    assertEquals(StatusCode.IO_FAILURE, tree.insert(key, 0, keyLength, workspace));
    assertEquals(StatusCode.OK, workspace.releaseRetained(pages));
  }

  @Test
  void cursorRetainsBorrowWhenLeafTransitionReleaseFails() {
    TupleShape shape = shape(new int[] {TEXT, SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(64);
    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, shape);
    TupleBTreeTreeWorkspace workspace = workspace();
    assertEquals(StatusCode.OK, tree.initialize(workspace));
    ByteBuffer key = ByteBuffer.allocate(2_048);
    for (int value = 0; value < 40; value++) {
      int length = key(key, 0, value, value + 1L);
      assertEquals(StatusCode.OK, tree.insert(key, 0, length, workspace));
    }
    assertTrue(pages.pageCount() > 1);

    TupleBTreeCursor cursor = new TupleBTreeCursor();
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK, cursor.openAll(tree, workspace));
    pages.failNextRelease();
    StatusCode status;
    do {
      status = cursor.next(entry);
    } while (status.isOk());
    assertEquals(StatusCode.IO_FAILURE, status);
    assertTrue(cursor.pageId() > 0);
    assertEquals(StatusCode.OK, cursor.close());
    assertEquals(0, cursor.pageId());
  }

  @Test
  void cursorRetriesBeforeCrossingLeafAfterRootGenerationChanges() {
    TupleShape shape = shape(new int[] {TEXT, SqlTypeDescriptor.BIGINT});
    TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(64);
    TupleBTree tree = new TupleBTree(pages, SCHEMA_ID, shape);
    TupleBTreeTreeWorkspace workspace = workspace();
    assertEquals(StatusCode.OK, tree.initialize(workspace));
    ByteBuffer key = ByteBuffer.allocate(2_048);
    for (int value = 0; value < 40; value++) {
      int length = key(key, 0, value, value + 1L);
      assertEquals(StatusCode.OK, tree.insert(key, 0, length, workspace));
    }
    TupleBTreeCursor cursor = new TupleBTreeCursor();
    TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
    assertEquals(StatusCode.OK, cursor.openAll(tree, workspace));
    pages.bumpRootGeneration();
    StatusCode status;
    do {
      status = cursor.next(entry);
    } while (status.isOk());
    assertEquals(StatusCode.RETRY, status);
    assertEquals(0, cursor.pageId());
  }

  private static void corruptFirstLeafLink(TupleBTreeTestPageProvider pages) {
    for (int pageId = 1; pageId <= pages.pageCount(); pageId++) {
      ByteBuffer page = pages.page(pageId);
      if (FormatBytes.getInt(page, 12) == TupleBTreePageCodec.TYPE_LEAF
          && FormatBytes.getInt(page, 24) != 0) {
        FormatBytes.putInt(page, 24, pageId);
        return;
      }
    }
  }

  private static boolean wouldSplit(
      TupleBTreeTestPageProvider pages, TupleShape shape, ByteBuffer key, int value) {
    ByteBuffer copy = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    TupleBTreePageSupport.copyPayload(pages.page(pages.rootPageId()), 0, copy, 0);
    ByteBuffer scratch = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    int length = key(key, 0, value, value + 1L);
    return TupleBTreeLeafPage.insert(
        copy, 0, scratch, 0, SCHEMA_ID, shape,
        key, 0, length, new TupleBTreeWorkspace()) == StatusCode.RESOURCE_EXHAUSTED;
  }

  private static int key(ByteBuffer target, int offset, long value, long rowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, 2));
    assertEquals(StatusCode.OK, builder.addText(TEXT, PREFIX));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishPhysical(rowId));
    return builder.keyBytes();
  }

  private static int userKey(ByteBuffer target, int offset, long value) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginTuple(target, offset, 2));
    assertEquals(StatusCode.OK, builder.addText(TEXT, PREFIX));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishTuple());
    return builder.keyBytes();
  }

  private static int prefix(ByteBuffer target) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginTuple(target, 0, 1));
    assertEquals(StatusCode.OK, builder.addText(TEXT, PREFIX));
    assertEquals(StatusCode.OK, builder.finishTuple());
    return builder.keyBytes();
  }

  private static int bigintKey(ByteBuffer target, int offset, long value) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishPhysical(value));
    return builder.keyBytes();
  }

  private static int approximateKey(
      ByteBuffer target, int offset, float single, double wide, long rowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, 2));
    assertEquals(StatusCode.OK, builder.addFixed(
        SqlTypeDescriptor.REAL, SqlApproximateNumeric.realBits(single)));
    assertEquals(StatusCode.OK, builder.addFixed(
        SqlTypeDescriptor.DOUBLE, SqlApproximateNumeric.doubleBits(wide)));
    assertEquals(StatusCode.OK, builder.finishPhysical(rowId));
    return builder.keyBytes();
  }

  private static void initializeLeaf(
      ByteBuffer page,
      TupleShape shape,
      int previous,
      int next,
      ByteBuffer highKey,
      int highOffset,
      int highLength,
      ByteBuffer key,
      int keyOffset,
      int keyLength) {
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, previous, next,
        shape, SCHEMA_ID, highKey, highOffset, highLength));
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        page, 0, shape, key, keyOffset, keyLength));
  }

  private static TupleBTreeTreeWorkspace workspace() {
    return new TupleBTreeTreeWorkspace(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES),
        ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES),
        new int[TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT],
        new int[TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT],
        new int[TupleBTreeTreeWorkspace.MAXIMUM_HEIGHT]);
  }

  private static TupleShape shape(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, result));
    return result.value();
  }
}
