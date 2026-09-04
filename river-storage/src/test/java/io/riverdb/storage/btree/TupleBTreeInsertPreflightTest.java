package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class TupleBTreeInsertPreflightTest {
  private static final long SCHEMA_ID = 117;
  private static final int TEXT = SqlTypeDescriptor.varchar(250);
  private static final String LARGE = "q".repeat(240);

  @Test
  void occupancyUsesTheExactValidatedPageBoundary() {
    TupleShape shape = shape();
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer key = ByteBuffer.allocate(4_096);
    int highLength = largeKey(key, 1_000);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initializeLeaf(
        page, 0, 0, 2, shape, SCHEMA_ID, key, 0, highLength));
    int firstLength = largeKey(key, 100);
    assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
        page, 0, shape, key, 0, firstLength));
    TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
    assertEquals(StatusCode.OK, TupleBTreePageAdmission.validate(
        page, 0, SCHEMA_ID, shape, TupleBTreePageCodec.TYPE_LEAF, workspace));

    int exactRemainingKeyBytes = workspace.header.freeEnd()
        - TupleBTreePageCodec.HEADER_BYTES
        - (workspace.header.entryCount() + 1) * TupleBTreePageCodec.SLOT_BYTES;
    assertTrue(TupleBTreePageOccupancy.accepts(exactRemainingKeyBytes, workspace));
    assertFalse(TupleBTreePageOccupancy.accepts(exactRemainingKeyBytes + 1, workspace));
  }

  @Test
  void reportsDuplicateNoSplitAndRootLeafSplitWithoutWriting() {
    Fixture fixture = new Fixture(64);
    ByteBuffer key = ByteBuffer.allocate(4_096);
    int length = fixture.key(key, 1);
    assertEquals(StatusCode.OK, fixture.tree.insert(key, 0, length, fixture.workspace));
    int root = fixture.pages.rootPageId();
    ByteBuffer snapshot = snapshot(fixture.pages.page(root));
    TupleBTreeInsertPreflightResult result = new TupleBTreeInsertPreflightResult();
    assertEquals(StatusCode.OK, fixture.tree.preflightInsert(
        key, 0, length, fixture.workspace, result));
    assertTrue(result.keyExists());
    assertEquals(0, result.changedPageCount());

    length = fixture.key(key, 2);
    assertEquals(StatusCode.OK, fixture.tree.preflightInsert(
        key, 0, length, fixture.workspace, result));
    assertFalse(result.keyExists());
    assertEquals(0, result.newPageCount());
    assertEquals(1, result.changedPageCount());
    assertPageEquals(snapshot, fixture.pages.page(root));

    int value = 2;
    do {
      length = fixture.key(key, value);
      assertEquals(StatusCode.OK, fixture.tree.preflightInsert(
          key, 0, length, fixture.workspace, result));
      if (result.createsRoot()) break;
      assertEquals(StatusCode.OK, fixture.tree.insert(key, 0, length, fixture.workspace));
      value++;
    } while (true);
    int pagesBefore = fixture.pages.pageCount();
    root = fixture.pages.rootPageId();
    snapshot = snapshot(fixture.pages.page(root));
    assertEquals(2, result.newPageCount());
    assertEquals(3, result.changedPageCount());
    assertEquals(1, result.splitLevelCount());
    assertPageEquals(snapshot, fixture.pages.page(root));
    assertEquals(pagesBefore, fixture.pages.pageCount());
  }

  @Test
  void modelsMaximumSuccessfulCascadeAndHeightExhaustion() {
    Cascade successful = Cascade.build(BTreeStructuralLimits.MAXIMUM_LEVELS - 2);
    TupleBTreeInsertPreflightResult result = new TupleBTreeInsertPreflightResult();
    assertEquals(StatusCode.OK, successful.tree.preflightInsert(
        successful.key, 0, successful.keyLength, successful.workspace, result));
    assertEquals(TupleBTreeInsertPreflightResult.MAXIMUM_NEW_PAGES, result.newPageCount());
    assertEquals(TupleBTreeInsertPreflightResult.MAXIMUM_CHANGED_PAGES - 1,
        result.changedPageCount());
    assertTrue(result.createsRoot());
    assertEquals(BTreeStructuralLimits.MAXIMUM_LEVELS, result.resultingHeight());

    Cascade exhausted = Cascade.build(BTreeStructuralLimits.MAXIMUM_LEVELS - 1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, exhausted.tree.preflightInsert(
        exhausted.key, 0, exhausted.keyLength, exhausted.workspace, result));
    assertEquals(0, result.changedPageCount());
    assertEquals(StatusCode.OK, exhausted.workspace.releaseRetained(exhausted.pages));
  }

  @Test
  void retainsReadBorrowWhenReleaseFails() {
    Fixture fixture = new Fixture(8);
    ByteBuffer key = ByteBuffer.allocate(4_096);
    int length = fixture.key(key, 7);
    fixture.pages.failNextRelease();
    TupleBTreeInsertPreflightResult result = new TupleBTreeInsertPreflightResult();
    assertEquals(StatusCode.IO_FAILURE, fixture.tree.preflightInsert(
        key, 0, length, fixture.workspace, result));
    assertEquals(0, result.changedPageCount());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, fixture.tree.preflightInsert(
        key, 0, length, fixture.workspace, result));
    assertEquals(StatusCode.OK, fixture.workspace.releaseRetained(fixture.pages));
    assertEquals(StatusCode.OK, fixture.tree.preflightInsert(
        key, 0, length, fixture.workspace, result));
  }

  private static ByteBuffer snapshot(ByteBuffer source) {
    ByteBuffer copy = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    TupleBTreePageSupport.copyPayload(source, 0, copy, 0);
    return copy;
  }

  private static void assertPageEquals(ByteBuffer expected, ByteBuffer actual) {
    for (int index = 0; index < PageCodec.MAX_PAYLOAD_BYTES; index++) {
      assertEquals(expected.get(index), actual.get(index));
    }
  }

  private static final class Fixture {
    final TupleShape shape = shape();
    final TupleBTreeTestPageProvider pages;
    final TupleBTree tree;
    final TupleBTreeTreeWorkspace workspace = workspace();

    Fixture(int maximumPages) {
      pages = new TupleBTreeTestPageProvider(maximumPages);
      tree = new TupleBTree(pages, SCHEMA_ID, shape);
      assertEquals(StatusCode.OK, tree.initialize(workspace));
    }

    int key(ByteBuffer target, long value) { return largeKey(target, value); }
  }

  private static final class Cascade {
    final TupleBTreeTestPageProvider pages;
    final TupleBTree tree;
    final TupleBTreeTreeWorkspace workspace;
    final ByteBuffer key;
    final int keyLength;

    private Cascade(
        TupleBTreeTestPageProvider provider, TupleBTree tupleTree,
        TupleBTreeTreeWorkspace treeWorkspace, ByteBuffer target, int length) {
      pages = provider;
      tree = tupleTree;
      workspace = treeWorkspace;
      key = target;
      keyLength = length;
    }

    static Cascade build(int internalLevels) {
      TupleShape shape = shape();
      TupleBTreeTestPageProvider pages = new TupleBTreeTestPageProvider(internalLevels + 4);
      TupleBTreePageReference reference = new TupleBTreePageReference();
      assertEquals(StatusCode.OK, pages.allocate(reference));
      int child = reference.pageId();
      assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
          reference.page(), 0, TupleBTreePageCodec.TYPE_LEAF, 0,
          shape, SCHEMA_ID, null, 0, 0));
      ByteBuffer target = ByteBuffer.allocate(4_096);
      int targetLength = largeKey(target, 1_000_000);
      ByteBuffer incoming = ByteBuffer.allocate(4_096);
      int incomingLength = fillLeafAndSeparator(reference.page(), shape, target, targetLength,
          incoming);
      assertEquals(StatusCode.OK, pages.release(reference));
      reference.reset();
      for (int level = 0; level < internalLevels; level++) {
        assertEquals(StatusCode.OK, pages.allocate(reference));
        int parent = reference.pageId();
        incomingLength = fillInternalAndPromote(
            reference.page(), shape, child, incoming, incomingLength);
        assertEquals(StatusCode.OK, pages.release(reference));
        reference.reset();
        child = parent;
      }
      pages.setRootPageId(child);
      return new Cascade(
          pages, new TupleBTree(pages, SCHEMA_ID, shape), workspace(), target, targetLength);
    }
  }

  private static int fillLeafAndSeparator(
      ByteBuffer page, TupleShape shape, ByteBuffer target, int targetLength,
      ByteBuffer incoming) {
    ByteBuffer key = ByteBuffer.allocate(4_096);
    for (int value = 100; value < 105; value++) {
      int length = largeKey(key, value);
      assertEquals(StatusCode.OK, TupleBTreePageCodec.appendLeaf(
          page, 0, shape, key, 0, length));
    }
    ByteBuffer left = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    TupleBTreeSplitResult split = new TupleBTreeSplitResult();
    assertEquals(StatusCode.OK, TupleBTreeLeafPage.splitInsert(
        page, 0, left, 0, right, 0, 98, 99, SCHEMA_ID, shape,
        target, 0, targetLength, new TupleBTreeWorkspace(), split));
    return copySeparator(split, incoming);
  }

  private static int fillInternalAndPromote(
      ByteBuffer page, TupleShape shape, int child,
      ByteBuffer incoming, int incomingLength) {
    assertEquals(StatusCode.OK, TupleBTreePageCodec.initialize(
        page, 0, TupleBTreePageCodec.TYPE_INTERNAL, child,
        shape, SCHEMA_ID, null, 0, 0));
    long center = TupleKeyCodec.logicalRowId(incoming, 0, incomingLength);
    ByteBuffer key = ByteBuffer.allocate(4_096);
    for (long value = center - 3; value <= center + 2; value++) {
      if (value == center) continue;
      int length = largeKey(key, value);
      assertEquals(StatusCode.OK, TupleBTreePageCodec.appendInternal(
          page, 0, shape, key, 0, length, child));
    }
    ByteBuffer left = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    TupleBTreeSplitResult split = new TupleBTreeSplitResult();
    assertEquals(StatusCode.OK, TupleBTreeInternalPage.splitInsert(
        page, 0, left, 0, right, 0, SCHEMA_ID, shape,
        incoming, 0, incomingLength, child, new TupleBTreeWorkspace(), split));
    return copySeparator(split, incoming);
  }

  private static int copySeparator(TupleBTreeSplitResult split, ByteBuffer target) {
    int length = split.separatorLength();
    for (int index = 0; index < length; index++) {
      target.put(index, split.separatorSource().get(split.separatorOffset() + index));
    }
    return length;
  }

  private static int largeKey(ByteBuffer target, long value) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, 0, 4));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.addText(TEXT, LARGE));
    assertEquals(StatusCode.OK, builder.addText(TEXT, LARGE));
    assertEquals(StatusCode.OK, builder.addText(TEXT, LARGE));
    assertEquals(StatusCode.OK, builder.finishPhysical(value));
    return builder.keyBytes();
  }

  private static TupleShape shape() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(
        new int[] {SqlTypeDescriptor.BIGINT, TEXT, TEXT, TEXT}, result));
    assertTrue(result.value().maximumEncodedBytes()
        <= TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES);
    return result.value();
  }

  private static TupleBTreeTreeWorkspace workspace() {
    return new TupleBTreeTreeWorkspace(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES),
        ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES),
        new int[BTreeStructuralLimits.MAXIMUM_LEVELS],
        new int[BTreeStructuralLimits.MAXIMUM_LEVELS],
        new int[BTreeStructuralLimits.MAXIMUM_LEVELS]);
  }
}
