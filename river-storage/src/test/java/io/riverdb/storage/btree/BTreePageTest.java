package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class BTreePageTest {
  private static final int PAGE_BYTES = 16 * 1024 - 128;

  @Test
  void insertsAndFindsOrderedLeafKeys() {
    ByteBuffer leaf = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreePage.initializeLeaf(leaf, 0, Long.MAX_VALUE));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 40, 4));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 10, 1));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 30, 3));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 20, 2));
    assertEquals(StatusCode.CONFLICT, BTreePage.insertLeaf(leaf, 20, 9));
    assertEquals(StatusCode.OK, BTreePage.validate(leaf));

    BTreeLookupResult lookup = new BTreeLookupResult();
    assertEquals(StatusCode.OK, BTreePage.lookupLeaf(leaf, 30, lookup));
    assertEquals(3, lookup.rowId());
    assertEquals(StatusCode.CONFLICT, BTreePage.lookupLeaf(leaf, 31, lookup));
  }

  @Test
  void splitsLeafAndRoutesThroughNewRoot() {
    ByteBuffer left = ByteBuffer.allocate(PAGE_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PAGE_BYTES);
    ByteBuffer root = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreePage.initializeLeaf(left, 0, Long.MAX_VALUE));
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      assertEquals(StatusCode.OK, BTreePage.insertLeaf(left, index * 2L, index + 1));
    }
    BTreeSplitResult split = new BTreeSplitResult();
    assertEquals(StatusCode.OK, BTreePage.splitLeaf(left, right, 4, 257, 258, split));
    assertEquals(4, BTreePage.rightSiblingPageId(left));
    assertEquals(split.separatorKey(), BTreePage.highKey(left));
    assertEquals(StatusCode.OK, BTreePage.validate(left));
    assertEquals(StatusCode.OK, BTreePage.validate(right));

    assertEquals(StatusCode.OK, BTreePage.initializeInternal(root, 3));
    assertEquals(StatusCode.OK, BTreePage.insertInternal(root, split.separatorKey(), 4));
    assertEquals(3, BTreePage.childForKey(root, split.separatorKey() - 1));
    assertEquals(4, BTreePage.childForKey(root, split.separatorKey()));
    assertEquals(StatusCode.OK, BTreePage.validate(root));

    BTreeLookupResult lookup = new BTreeLookupResult();
    assertEquals(StatusCode.OK, BTreePage.lookupLeaf(right, 257, lookup));
    assertEquals(258, lookup.rowId());
  }

  @Test
  void allocatesAndPublishesRootMetadata() {
    ByteBuffer metadata = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreeRootPage.initialize(metadata, 3, 4));
    assertEquals(4, BTreeRootPage.allocatePage(metadata));
    assertEquals(5, BTreeRootPage.allocatePage(metadata));
    BTreeRootPage.publishRoot(metadata, 5);
    assertEquals(5, BTreeRootPage.rootPageId(metadata));
    assertEquals(6, BTreeRootPage.nextPageId(metadata));
    assertEquals(StatusCode.OK, BTreeRootPage.validate(metadata));
  }

  @Test
  void splitsInternalPageAndPromotesMiddleSeparator() {
    ByteBuffer left = ByteBuffer.allocate(PAGE_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreePage.initializeInternal(left, 1000));
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      assertEquals(
          StatusCode.OK,
          BTreePage.insertInternal(left, index * 2L + 2, 1001 + index));
    }
    BTreeSplitResult split = new BTreeSplitResult();
    assertEquals(
        StatusCode.OK,
        BTreePage.splitInternal(left, right, 257, 2000, split));
    assertEquals(257, split.separatorKey());
    assertEquals(128, BTreePage.entryCount(left));
    assertEquals(128, BTreePage.entryCount(right));
    assertEquals(1000, BTreePage.childForKey(left, 1));
    assertEquals(2000, BTreePage.childForKey(right, 257));
    assertEquals(2000, BTreePage.firstChildPageId(right));
    assertEquals(257, BTreePage.highKey(left));
    assertEquals(Long.MAX_VALUE, BTreePage.highKey(right));
    assertEquals(StatusCode.OK, BTreePage.validate(left));
    assertEquals(StatusCode.OK, BTreePage.validate(right));
  }
}
