package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTreeStructureTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(811, 821);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int ROOT_PAGE_ID = 3;
  private static final int INTERNAL_PAGES = 9;
  private static final int TARGET_LEAF_PAGE_ID = ROOT_PAGE_ID + INTERNAL_PAGES;
  private static final int FIRST_SIDE_LEAF_PAGE_ID = TARGET_LEAF_PAGE_ID + 1;
  private static final int NEXT_PAGE_ID = FIRST_SIDE_LEAF_PAGE_ID + INTERNAL_PAGES;

  @Test
  void derivesHeightFromMinimumBranchingAndPositiveIntPageIds() {
    assertEquals(0, BTreeStructuralLimits.maximumLevelsForPageCount(0));
    assertEquals(1, BTreeStructuralLimits.maximumLevelsForPageCount(1));
    assertEquals(9, BTreeStructuralLimits.maximumLevelsForPageCount(1_022));
    assertEquals(10, BTreeStructuralLimits.maximumLevelsForPageCount(1_023));
    assertEquals(30, BTreeStructuralLimits.MAXIMUM_LEVELS);
    assertTrue(BTreeStructuralLimits.canVisitLevel(29));
    assertFalse(BTreeStructuralLimits.canVisitLevel(30));
    assertTrue(BTreeStructuralLimits.canDescendFrom(28));
    assertFalse(BTreeStructuralLimits.canDescendFrom(29));
    assertTrue(BTreeStructuralLimits.validPageId(IndexedTableLimits.MAX_PAGES));
    assertFalse(BTreeStructuralLimits.validPageId(Integer.MAX_VALUE));
  }

  @Test
  void lookupTraversesMoreThanEightLevelsAndRejectsCycle(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageFile = new DirectoryOperationResult();
    DirectoryOperationResult stagingFile = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageFile));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingFile));
    IndexedPageSet pages = new IndexedPageSet(
        pageFile.file(), stagingFile.file(), DATABASE, GENERATION,
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(32, 32, 32));

    ByteBuffer metadata = stageScalar(pages, IndexedTableKernel.ROOT_META_PAGE_ID);
    assertEquals(
        StatusCode.OK, BTreeRootPage.initialize(metadata, ROOT_PAGE_ID, NEXT_PAGE_ID));
    for (int level = 0; level < INTERNAL_PAGES; level++) {
      int pageId = ROOT_PAGE_ID + level;
      int firstChild = level + 1 == INTERNAL_PAGES
          ? TARGET_LEAF_PAGE_ID : pageId + 1;
      ByteBuffer internal = stageScalar(pages, pageId);
      assertEquals(StatusCode.OK, BTreePage.initializeInternal(internal, firstChild));
      assertEquals(StatusCode.OK, BTreePage.insertInternal(
          internal, 0, 1, FIRST_SIDE_LEAF_PAGE_ID + level));
      assertEquals(StatusCode.OK, BTreePage.validate(internal));
    }
    ByteBuffer target = stageScalar(pages, TARGET_LEAF_PAGE_ID);
    assertEquals(StatusCode.OK, BTreePage.initializeLeaf(target, 0));
    for (int level = 0; level < INTERNAL_PAGES; level++) {
      ByteBuffer side = stageScalar(pages, FIRST_SIDE_LEAF_PAGE_ID + level);
      assertEquals(StatusCode.OK, BTreePage.initializeLeaf(side, 0));
    }
    publish(pages, 1);

    int[] path = new int[BTreeStructuralLimits.MAXIMUM_INTERNAL_LEVELS];
    IndexedTreeLookup lookup = new IndexedTreeLookup(pages, path);
    assertEquals(TARGET_LEAF_PAGE_ID, lookup.find(0, 0, false, true));
    assertEquals(StatusCode.OK, lookup.lastStatus());
    assertEquals(INTERNAL_PAGES, lookup.pathDepth());

    ByteBuffer cycle = pages.stageExisting(ROOT_PAGE_ID, 1);
    assertNotNull(cycle);
    assertEquals(StatusCode.OK, BTreePage.initializeInternal(cycle, ROOT_PAGE_ID));
    assertEquals(StatusCode.OK, BTreePage.insertInternal(
        cycle, 0, 1, FIRST_SIDE_LEAF_PAGE_ID));
    publish(pages, 2);
    assertEquals(0, lookup.find(0, 0, false, true));
    assertEquals(StatusCode.CORRUPTION, lookup.lastStatus());

    assertEquals(StatusCode.OK, pages.detach());
    assertEquals(StatusCode.OK, pageFile.file().close());
    assertEquals(StatusCode.OK, stagingFile.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static ByteBuffer stageScalar(IndexedPageSet pages, int pageId) {
    ByteBuffer page = pages.stageNew(
        pageId, 32, PageCodec.PAYLOAD_KIND_SCALAR_BTREE, PageCodec.SCALAR_OWNER_KEY_ID);
    assertNotNull(page);
    return page;
  }

  private static void publish(IndexedPageSet pages, long commitSequence) {
    assertEquals(StatusCode.OK, pages.beginPreparedBatch());
    assertEquals(StatusCode.OK, pages.freezeChangedPages(0, Long.MAX_VALUE));
    assertEquals(StatusCode.OK, pages.installPreparedPages(
        new long[] {commitSequence}, 1, commitSequence, commitSequence + 1));
    assertEquals(StatusCode.OK, pages.releasePreparedBatch());
    pages.resetChanges();
  }
}
