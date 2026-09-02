package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class BTreePageTest {
  private static final int PAGE_BYTES = 16 * 1024 - 128;

  @Test
  void insertsAndFindsOrderedLeafKeys() {
    ByteBuffer leaf = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreePage.initializeLeaf(leaf, 0));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 3, 40, 4));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 3, 10, 1));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 3, 30, 3));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 3, 20, 2));
    assertEquals(StatusCode.CONFLICT, BTreePage.insertLeaf(leaf, 3, 20, 9));
    assertEquals(StatusCode.OK, BTreePage.validate(leaf));

    BTreeLookupResult lookup = new BTreeLookupResult();
    assertEquals(StatusCode.OK, BTreePage.lookupLeaf(leaf, 3, 30, lookup));
    assertEquals(3, lookup.rowId());
    assertEquals(StatusCode.CONFLICT, BTreePage.lookupLeaf(leaf, 3, 31, lookup));
  }

  @Test
  void splitsLeafAndRoutesThroughNewRoot() {
    ByteBuffer left = ByteBuffer.allocate(PAGE_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PAGE_BYTES);
    ByteBuffer root = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreePage.initializeLeaf(left, 0));
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      assertEquals(StatusCode.OK, BTreePage.insertLeaf(left, 7, index * 2L, index + 1));
    }
    BTreeSplitResult split = new BTreeSplitResult();
    assertEquals(
        StatusCode.OK, BTreePage.splitLeaf(left, right, 4, 7, 257, 258, split));
    assertEquals(4, BTreePage.rightSiblingPageId(left));
    assertEquals(split.separatorKey(), BTreePage.highKey(left));
    assertEquals(StatusCode.OK, BTreePage.validate(left));
    assertEquals(StatusCode.OK, BTreePage.validate(right));

    assertEquals(StatusCode.OK, BTreePage.initializeInternal(root, 3));
    assertEquals(StatusCode.OK, BTreePage.insertInternal(
        root, split.separatorSpace(), split.separatorKey(), 4));
    assertEquals(3, BTreePage.childForKey(root, 7, split.separatorKey() - 1));
    assertEquals(4, BTreePage.childForKey(root, 7, split.separatorKey()));
    assertEquals(StatusCode.OK, BTreePage.validate(root));

    BTreeLookupResult lookup = new BTreeLookupResult();
    assertEquals(StatusCode.OK, BTreePage.lookupLeaf(right, 7, 257, lookup));
    assertEquals(258, lookup.rowId());
  }

  @Test
  void allocatesAndPublishesRootMetadata() {
    ByteBuffer metadata = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreeRootPage.initialize(metadata, 3, 4));
    assertEquals(StatusCode.OK, BTreeRootPage.allocatePage(metadata, 4, -1));
    assertEquals(StatusCode.OK, BTreeRootPage.allocatePage(metadata, 5, -1));
    BTreeRootPage.publishRoot(metadata, 5);
    assertEquals(5, BTreeRootPage.rootPageId(metadata));
    assertEquals(6, BTreeRootPage.nextPageId(metadata));
    assertEquals(StatusCode.OK, BTreeRootPage.validate(metadata));
  }

  @Test
  void intrusiveFreeStackScalesAndReusesLifo() {
    ByteBuffer metadata = ByteBuffer.allocate(BTreeRootPage.BYTES);
    ByteBuffer fourth = ByteBuffer.allocate(64);
    ByteBuffer fifth = ByteBuffer.allocate(64);
    ByteBuffer sixth = ByteBuffer.allocate(64);
    assertEquals(StatusCode.OK, BTreeRootPage.initialize(metadata, 3, 7));
    assertEquals(StatusCode.OK, BTreeRootPage.releasePage(metadata, 4, fourth));
    assertEquals(StatusCode.OK, BTreeRootPage.releasePage(metadata, 5, fifth));
    assertEquals(StatusCode.OK, BTreeRootPage.releasePage(metadata, 6, sixth));
    assertEquals(3, BTreeRootPage.freePageCount(metadata));
    assertEquals(6, BTreeRootPage.nextAllocationPage(metadata));
    assertEquals(StatusCode.OK, BTreeRootPage.allocatePage(metadata, 6, 5));
    assertEquals(StatusCode.OK, BTreeRootPage.allocatePage(metadata, 5, 4));
    assertEquals(StatusCode.OK, BTreeRootPage.allocatePage(metadata, 4, 0));
    assertEquals(StatusCode.OK, BTreeRootPage.allocatePage(metadata, 7, -1));
    assertEquals(8, BTreeRootPage.nextPageId(metadata));
    assertEquals(StatusCode.OK, BTreeRootPage.validate(metadata));
  }

  @Test
  void intrusiveFreeStackExceedsFormerRootArrayCapacity() {
    int nextPageId = 4_200;
    ByteBuffer metadata = ByteBuffer.allocate(BTreeRootPage.BYTES);
    ByteBuffer[] freePages = new ByteBuffer[nextPageId];
    assertEquals(StatusCode.OK, BTreeRootPage.initialize(metadata, 3, nextPageId));
    for (int pageId = 4; pageId < nextPageId; pageId++) {
      freePages[pageId] = ByteBuffer.allocate(BTreeFreePage.BYTES);
      assertEquals(StatusCode.OK,
          BTreeRootPage.releasePage(metadata, pageId, freePages[pageId]));
    }
    assertEquals(nextPageId - 4, BTreeRootPage.freePageCount(metadata));
    for (int pageId = nextPageId - 1; pageId >= 4; pageId--) {
      assertEquals(pageId, BTreeRootPage.nextAllocationPage(metadata));
      assertEquals(StatusCode.OK, BTreeRootPage.allocatePage(
          metadata, pageId, BTreeFreePage.nextPageId(freePages[pageId])));
    }
    assertEquals(0, BTreeRootPage.freePageCount(metadata));
  }

  @Test
  void splitsInternalPageAndPromotesMiddleSeparator() {
    ByteBuffer left = ByteBuffer.allocate(PAGE_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreePage.initializeInternal(left, 1000));
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      assertEquals(
          StatusCode.OK,
          BTreePage.insertInternal(left, 5, index * 2L + 2, 1001 + index));
    }
    BTreeSplitResult split = new BTreeSplitResult();
    assertEquals(
        StatusCode.OK,
        BTreePage.splitInternal(left, right, 5, 257, 2000, split));
    assertEquals(257, split.separatorKey());
    assertEquals(128, BTreePage.entryCount(left));
    assertEquals(128, BTreePage.entryCount(right));
    assertEquals(1000, BTreePage.childForKey(left, 5, 1));
    assertEquals(2000, BTreePage.childForKey(right, 5, 257));
    assertEquals(2000, BTreePage.firstChildPageId(right));
    assertEquals(257, BTreePage.highKey(left));
    assertEquals(OrderedKey.INFINITY_SPACE, BTreePage.highSpace(right));
    assertEquals(0, BTreePage.highKey(right));
    assertEquals(StatusCode.OK, BTreePage.validate(left));
    assertEquals(StatusCode.OK, BTreePage.validate(right));
  }

  @Test
  void ordersFullSignedKeysAcrossSpacesAndRejectsNonCanonicalInfinity() {
    ByteBuffer leaf = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreePage.initializeLeaf(leaf, 0));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 2, Long.MAX_VALUE, 3));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 2, Long.MIN_VALUE, 1));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 3, Long.MIN_VALUE, 4));
    assertEquals(StatusCode.OK, BTreePage.insertLeaf(leaf, 2, 0, 2));
    assertEquals(Long.MIN_VALUE, BTreePage.keyAt(leaf, 0));
    assertEquals(2, BTreePage.spaceAt(leaf, 0));
    assertEquals(Long.MAX_VALUE, BTreePage.keyAt(leaf, 2));
    assertEquals(3, BTreePage.spaceAt(leaf, 3));
    assertEquals(StatusCode.OK, BTreePage.validate(leaf));
    leaf.put(24, (byte) 1);
    assertEquals(StatusCode.CORRUPTION, BTreePage.validate(leaf));
  }

  @Test
  void splitPropagatesCrossSpaceSeparatorPair() {
    ByteBuffer left = ByteBuffer.allocate(PAGE_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreePage.initializeLeaf(left, 0));
    for (int index = 0; index < BTreePage.MAX_ENTRIES / 2; index++) {
      assertEquals(
          StatusCode.OK,
          BTreePage.insertLeaf(left, 2, Long.MIN_VALUE + index, index + 1));
      assertEquals(
          StatusCode.OK,
          BTreePage.insertLeaf(
              left, 3, Long.MIN_VALUE + index,
              BTreePage.MAX_ENTRIES / 2 + index + 1));
    }
    BTreeSplitResult split = new BTreeSplitResult();
    assertEquals(
        StatusCode.OK,
        BTreePage.splitLeaf(left, right, 4, 3, Long.MAX_VALUE, 300, split));
    assertEquals(3, split.separatorSpace());
    assertEquals(Long.MIN_VALUE, split.separatorKey());
    assertEquals(split.separatorSpace(), BTreePage.highSpace(left));
    assertEquals(split.separatorKey(), BTreePage.highKey(left));
    assertEquals(BTreePage.spaceAt(right, 0), BTreePage.highSpace(left));
    assertEquals(BTreePage.keyAt(right, 0), BTreePage.highKey(left));
    assertEquals(OrderedKey.INFINITY_SPACE, BTreePage.highSpace(right));
    assertEquals(0, BTreePage.highKey(right));
    assertEquals(StatusCode.OK, BTreePage.validate(left));
    assertEquals(StatusCode.OK, BTreePage.validate(right));
  }

  @Test
  void preservesLongMaximumSpaceThroughLookupAndSplit() {
    ByteBuffer left = ByteBuffer.allocate(PAGE_BYTES);
    ByteBuffer right = ByteBuffer.allocate(PAGE_BYTES);
    assertEquals(StatusCode.OK, BTreePage.initializeLeaf(left, 0));
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      assertEquals(StatusCode.OK,
          BTreePage.insertLeaf(left, Long.MAX_VALUE, index * 2L, index + 1));
    }
    BTreeSplitResult split = new BTreeSplitResult();
    assertEquals(StatusCode.OK, BTreePage.splitLeaf(
        left, right, 4, Long.MAX_VALUE, 257, 258, split));
    assertEquals(Long.MAX_VALUE, split.separatorSpace());
    BTreeLookupResult lookup = new BTreeLookupResult();
    assertEquals(StatusCode.OK,
        BTreePage.lookupLeaf(right, Long.MAX_VALUE, 257, lookup));
    assertEquals(258, lookup.rowId());
    assertEquals(StatusCode.OK, BTreePage.validate(left));
    assertEquals(StatusCode.OK, BTreePage.validate(right));
  }
}
