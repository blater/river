package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.engine.runtime.DatabasePageCacheTestPlan;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedPageCacheEvictionTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(461, 463);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int PAGE_COUNT = 4_097;
  private static final io.riverdb.engine.runtime.DatabasePageCachePlan LARGE_TEST_CACHE =
      io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(4_096, 128, 127);

  @Test
  void publishedGenerationNeverMutatesPinnedBorrow(@TempDir Path root) {
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
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(4, 2, 2));

    ByteBuffer first = pages.stageNew(
        1, 2, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, 41);
    assertNotNull(first);
    first.putInt(0, 11);
    publishPrepared(pages, Long.MAX_VALUE, 1, 2, 1);
    pages.resetChanges();

    IndexedPageGenerationPin old = new IndexedPageGenerationPin();
    assertEquals(StatusCode.OK, pages.pinPageAt(1, 1, old));
    assertEquals(11, old.payload().getInt(0));
    ByteBuffer second = pages.stageExisting(1, 2);
    assertNotNull(second);
    second.putInt(0, 22);
    publishPrepared(pages, 1, 2, 3, 2);
    pages.resetChanges();

    assertEquals(11, old.payload().getInt(0));
    assertEquals(StatusCode.OK, pages.reclaimHistorical(2));
    ByteBuffer third = pages.stageExisting(1, 2);
    assertNotNull(third);
    third.putInt(0, 33);
    publishPrepared(pages, 2, 3, 4, 3);
    pages.resetChanges();

    assertEquals(11, old.payload().getInt(0));
    IndexedPageGenerationPin current = new IndexedPageGenerationPin();
    assertEquals(StatusCode.OK, pages.pinPageAt(1, 3, current));
    assertEquals(33, current.payload().getInt(0));
    assertEquals(StatusCode.OK, pages.unpinPage(current));
    assertEquals(StatusCode.OK, pages.unpinPage(old));
    assertEquals(StatusCode.OK, pages.reclaimHistorical(3));
    assertEquals(StatusCode.CORRUPTION,
        pages.pinPageAt(1, 1, new IndexedPageGenerationPin()));
    assertEquals(StatusCode.OK, pageFile.file().close());
    assertEquals(StatusCode.OK, stagingFile.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void reusesReclaimedFrameBeforeAllocatingUnusedCapacity(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageFile = new DirectoryOperationResult();
    DirectoryOperationResult stagingFile = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageFile));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingFile));
    DatabasePageCachePlan plan = DatabasePageCacheTestPlan.geometry(4, 2, 2);
    IndexedPageState state = new IndexedPageState(plan);
    WriteCountingFile countedPages = new WriteCountingFile(pageFile.file());
    IndexedPageFrameCache cache = new IndexedPageFrameCache(
        countedPages, stagingFile.file(), DATABASE, GENERATION, state, plan);

    ByteBuffer first = cache.stageNew(1, 2);
    assertNotNull(first);
    first.putInt(0, 11);
    publishPrepared(cache, 1, 1, 2);
    state.resetChanges();

    assertEquals(StatusCode.OK, cache.reclaimHistorical(1));
    ByteBuffer second = cache.stageExisting(1, 2);
    assertNotNull(second);
    second.putInt(0, 22);
    publishPrepared(cache, 2, 2, 3);
    state.resetChanges();
    assertEquals(2, allocatedCurrentFrames(cache));

    assertEquals(StatusCode.OK, cache.reclaimHistorical(1));
    IndexedPageGenerationPin stillVisible = new IndexedPageGenerationPin();
    assertEquals(StatusCode.OK, cache.pinPageAt(1, 1, stillVisible));
    assertEquals(11, stillVisible.payload().getInt(0));
    assertEquals(StatusCode.OK, cache.unpinPage(stillVisible));
    int writesBeforeReclaim = countedPages.writes;
    assertEquals(StatusCode.OK, cache.reclaimHistorical(2));
    assertEquals(writesBeforeReclaim, countedPages.writes);
    ByteBuffer third = cache.stageExisting(1, 2);
    assertNotNull(third);
    third.putInt(0, 33);
    publishPrepared(cache, 3, 3, 4);
    state.resetChanges();

    assertEquals(2, allocatedCurrentFrames(cache));
    assertEquals(33, cache.currentPayload(1).getInt(0));
    assertEquals(StatusCode.OK, countedPages.close());
    assertEquals(StatusCode.OK, stagingFile.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void releasesTheExactPreparedGenerationAfterALaterSamePageFreeze(
      @TempDir Path root) {
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
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(4, 2, 2));
    IndexedOperationPage writer = new IndexedOperationPage();
    IndexedOperationPage firstGeneration = new IndexedOperationPage();

    assertEquals(StatusCode.OK, pages.pinNewScalarOperationPage(1, writer));
    writer.payload().putLong(0, 11);
    assertEquals(StatusCode.OK, pages.releaseOperationPage(writer));
    assertEquals(StatusCode.OK, pages.beginPreparedBatch());
    assertEquals(StatusCode.OK, pages.freezeChangedPages(0, Long.MAX_VALUE));
    assertEquals(StatusCode.OK, pages.pinOperationPage(1, false, firstGeneration));
    assertEquals(11, firstGeneration.payload().getLong(0));

    assertEquals(StatusCode.OK, pages.pinOperationPage(1, true, writer));
    writer.payload().putLong(0, 22);
    assertEquals(StatusCode.OK, pages.releaseOperationPage(writer));
    assertEquals(StatusCode.OK, pages.freezeChangedPages(1, Long.MAX_VALUE));
    assertEquals(StatusCode.OK, pages.releaseOperationPage(firstGeneration));
    assertEquals(StatusCode.OK,
        pages.installPreparedPages(new long[] {1, 2}, 2, 1, 2));
    assertEquals(StatusCode.OK, pages.releasePreparedBatch());
    assertEquals(22, pages.currentPayload(1).getLong(0));

    assertEquals(StatusCode.OK, pageFile.file().close());
    assertEquals(StatusCode.OK, stagingFile.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsFreezeUntilEveryStagingBorrowIsReturned(@TempDir Path root) {
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
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(2, 1, 1));
    IndexedOperationPage writer = new IndexedOperationPage();

    assertEquals(StatusCode.OK, pages.pinNewScalarOperationPage(1, writer));
    assertEquals(StatusCode.OK, pages.beginPreparedBatch());
    assertEquals(StatusCode.INVARIANT_BROKEN,
        pages.freezeChangedPages(0, Long.MAX_VALUE));
    assertEquals(StatusCode.OK, pages.releaseOperationPage(writer));
    assertEquals(StatusCode.OK, pages.freezeChangedPages(0, Long.MAX_VALUE));
    assertEquals(StatusCode.OK,
        pages.installPreparedPages(new long[] {1}, 1, 1, 2));
    assertEquals(StatusCode.OK, pages.releasePreparedBatch());

    assertEquals(StatusCode.OK, pageFile.file().close());
    assertEquals(StatusCode.OK, stagingFile.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsCommitBoundariesBeforeInstallingPreparedPages(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageFile = new DirectoryOperationResult();
    DirectoryOperationResult stagingFile = new DirectoryOperationResult();
    DirectoryOperationResult rowFile = new DirectoryOperationResult();
    DirectoryOperationResult versionFile = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageFile));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingFile));
    assertEquals(StatusCode.OK, directory.createFile("rows", rowFile));
    assertEquals(StatusCode.OK, directory.createFile("versions", versionFile));
    DatabasePageCachePlan cachePlan = DatabasePageCacheTestPlan.geometry(2, 1, 1);
    IndexedPageSet pages = new IndexedPageSet(
        pageFile.file(), stagingFile.file(), DATABASE, GENERATION, cachePlan);
    IndexedTableKernel kernel = pages.createKernel(
        rowFile.file(), versionFile.file(),
        DatabasePageCacheTestPlan.governor(cachePlan, 1).plan());
    IndexedLogicalRowIdRegistry logicalRows = new IndexedLogicalRowIdRegistry();
    IndexedPreparedCommitInstaller installer =
        new IndexedPreparedCommitInstaller(kernel, pages, logicalRows);
    IndexedRelationalMutationBuffer mutation =
        new IndexedRelationalMutationBuffer(1, 0, 0);
    assertEquals(StatusCode.OK, logicalRows.admit(1, 1));
    assertEquals(StatusCode.OK, mutation.appendLogicalRowFloor(1, 1));
    assertEquals(StatusCode.OK, mutation.seal());
    IndexedRelationalMutationBuffer[] mutations = {mutation};
    long[] sequences = {1};
    long[] rowEnds = {0};
    int[] heapEnds = {IndexedTableKernel.HEAP_PAGE_ID};

    kernel.beginOperationState();
    assertNotNull(pages.stageNew(1, IndexedTableLimits.MAX_CHANGED_PAGES));
    assertEquals(StatusCode.OK, pages.beginPreparedBatch());
    assertEquals(StatusCode.OK, pages.freezeChangedPages(0, Long.MAX_VALUE));
    rowEnds[0] = 1;
    assertEquals(StatusCode.INVARIANT_BROKEN,
        installer.install(mutations, sequences, rowEnds, heapEnds, 1, 0, 1, 2, false));
    assertEquals(false, pages.isPresent(1));
    rowEnds[0] = 0;
    heapEnds[0]++;
    assertEquals(StatusCode.INVARIANT_BROKEN,
        installer.install(mutations, sequences, rowEnds, heapEnds, 1, 0, 1, 2, false));
    assertEquals(false, pages.isPresent(1));
    heapEnds[0]--;
    assertEquals(StatusCode.OK,
        installer.install(mutations, sequences, rowEnds, heapEnds, 1, 0, 1, 2, false));
    assertEquals(StatusCode.OK, pages.releasePreparedBatch());
    assertEquals(true, pages.isPresent(1));

    assertEquals(StatusCode.OK, pageFile.file().close());
    assertEquals(StatusCode.OK, stagingFile.file().close());
    assertEquals(StatusCode.OK, rowFile.file().close());
    assertEquals(StatusCode.OK, versionFile.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void keepsPinnedPageStableAcrossWorkingSetLargerThanCache(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageOperation = new DirectoryOperationResult();
    DirectoryOperationResult stagingOperation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageOperation));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingOperation));
    DurableFile pagesFile = pageOperation.file();
    DurableFile stagingFile = stagingOperation.file();
    IndexedPageSet pages = new IndexedPageSet(
        pagesFile, stagingFile, DATABASE, GENERATION, LARGE_TEST_CACHE);
    ByteBuffer encoded = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
    CRC32C checksum = new CRC32C();
    ByteBuffer pinned = null;

    for (int pageId = 1; pageId <= PAGE_COUNT; pageId++) {
      encoded.clear();
      encoded.putInt(PageCodec.HEADER_BYTES, pageId);
      assertEquals(
          StatusCode.OK,
          PageCodec.encode(
              DATABASE,
              GENERATION,
              pageId,
              1,
              1,
              2,
              PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
              PageCodec.SCALAR_OWNER_KEY_ID,
              Integer.BYTES,
              encoded,
              checksum));
      assertEquals(StatusCode.OK, pages.installFromRecord(encoded, 0, pageId, 1, 2));
      if (pageId == 1) {
        assertEquals(StatusCode.OK, pages.pinCurrentPage(1));
        pinned = pages.currentPayload(1);
        assertEquals(1, pinned.getInt(0));
      }
    }

    assertEquals(1, pinned.getInt(0));
    pages.unpinCurrentPage(1);
    assertEquals(1, pages.currentPayload(1).getInt(0));

    assertEquals(StatusCode.OK, pagesFile.close());
    assertEquals(StatusCode.OK, stagingFile.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsConflictingIdentityForAlreadyStagedNewPage(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageOperation = new DirectoryOperationResult();
    DirectoryOperationResult stagingOperation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageOperation));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingOperation));
    DurableFile pagesFile = pageOperation.file();
    DurableFile stagingFile = stagingOperation.file();
    IndexedPageSet pages = new IndexedPageSet(
        pagesFile, stagingFile, DATABASE, GENERATION, LARGE_TEST_CACHE);

    assertNotNull(pages.stageNew(
        1,
        IndexedTableLimits.MAX_CHANGED_PAGES,
        PageCodec.PAYLOAD_KIND_TUPLE_BTREE,
        41));
    assertNull(pages.stageNew(
        1,
        IndexedTableLimits.MAX_CHANGED_PAGES,
        PageCodec.PAYLOAD_KIND_TUPLE_BTREE,
        43));
    assertEquals(StatusCode.CORRUPTION, pages.lastStatus());
    assertNull(pages.stageNew(
        1,
        IndexedTableLimits.MAX_CHANGED_PAGES,
        PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
        PageCodec.SCALAR_OWNER_KEY_ID));
    assertEquals(StatusCode.CORRUPTION, pages.lastStatus());

    assertEquals(StatusCode.OK, pagesFile.close());
    assertEquals(StatusCode.OK, stagingFile.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void keepsPageImageAndLogicalChangeBoundsSeparate(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageOperation = new DirectoryOperationResult();
    DirectoryOperationResult stagingOperation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageOperation));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingOperation));
    io.riverdb.engine.runtime.DatabasePageCachePlan scaleConfig =
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(128, 2, 256);
    IndexedPageSet pages = new IndexedPageSet(
        pageOperation.file(), stagingOperation.file(), DATABASE, GENERATION,
        scaleConfig);
    IndexedOperationPage page = new IndexedOperationPage();

    pages.beginPageImageOperation();
    for (int pageId = 1; pageId <= IndexedTableLimits.MAX_CHANGED_PAGES; pageId++) {
      assertEquals(StatusCode.OK, pages.pinNewOperationPage(pageId, page));
      assertEquals(StatusCode.OK, pages.releaseOperationPage(page));
    }
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        pages.pinNewTupleOperationPage(
            IndexedTableLimits.MAX_CHANGED_PAGES + 1, 1, page));
    pages.clearStagedFlags();
    pages.resetChanges();
    int logicalCapacity = pages.changedPageCapacity();
    assertEquals(256, logicalCapacity);
    for (int pageId = 1; pageId <= logicalCapacity; pageId++) {
      assertEquals(StatusCode.OK, pages.pinNewScalarOperationPage(pageId, page));
      assertEquals(StatusCode.OK, pages.releaseOperationPage(page));
    }
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        pages.pinNewScalarOperationPage(
            logicalCapacity + 1, page));
    pages.clearStagedFlags();
    pages.resetChanges();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        pages.pinNewTupleOperationPage(1, CatalogKeyspace.KEY_ID_EXHAUSTED, page));
    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        new IndexedTupleRootState(
            CatalogKeyspace.KEY_ID_EXHAUSTED, 1, 0).begin());

    assertEquals(StatusCode.OK, pageOperation.file().close());
    assertEquals(StatusCode.OK, stagingOperation.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void boundedMetadataPressureIsRetryableAndReopensIdentity(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageFile = new DirectoryOperationResult();
    DirectoryOperationResult stagingFile = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageFile));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingFile));
    io.riverdb.engine.runtime.DatabasePageCachePlan config =
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(2, 2, 2);
    IndexedPageSet pages = new IndexedPageSet(
        pageFile.file(), stagingFile.file(), DATABASE, GENERATION, config);
    IndexedOperationPage page = new IndexedOperationPage();

    assertEquals(StatusCode.OK, pages.pinNewTupleOperationPage(1, 41, page));
    assertEquals(StatusCode.OK, pages.releaseOperationPage(page));
    assertEquals(StatusCode.OK, pages.pinNewTupleOperationPage(2, 41, page));
    assertEquals(StatusCode.OK, pages.releaseOperationPage(page));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, pages.pinNewTupleOperationPage(3, 41, page));
    assertEquals(false, page.attached());
    assertEquals(2, pages.changedPageCount());

    pages.clearStagedFlags();
    pages.resetChanges();
    assertEquals(StatusCode.OK, pages.pinNewTupleOperationPage(3, 41, page));
    assertEquals(StatusCode.OK, pages.releaseOperationPage(page));
    publishPrepared(pages, Long.MAX_VALUE, 1, 2, 1);
    assertEquals(StatusCode.OK, pages.encodeCurrent(3, DATABASE, GENERATION, 1, 2, new CRC32C()));
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK,
        pages.writeCurrent(
            pageFile.file(), 3, 3L * PageCodec.PAGE_BYTES - PageCodec.PAGE_BYTES, io));
    IndexedPageSet reopened = new IndexedPageSet(
        pageFile.file(), stagingFile.file(), DATABASE, GENERATION, config);
    assertEquals(StatusCode.OK, reopened.installPresent(3));
    assertEquals(StatusCode.OK, reopened.pinCurrentPage(3));
    assertEquals(PageCodec.PAYLOAD_KIND_TUPLE_BTREE, reopened.payloadKind(3));
    assertEquals(41, reopened.ownerKeyId(3));
    reopened.unpinCurrentPage(3);
    assertEquals(StatusCode.OK, pageFile.file().close());
    assertEquals(StatusCode.OK, stagingFile.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void validatesResidentIdentityBeforePresencePublication(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageFile = new DirectoryOperationResult();
    DirectoryOperationResult stagingFile = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageFile));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingFile));
    ByteBuffer encoded = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
    assertEquals(StatusCode.OK, PageCodec.encode(
        DATABASE, GENERATION, 1, 1, 0, 0,
        PageCodec.PAYLOAD_KIND_TUPLE_BTREE, 41, 0, encoded, new CRC32C()));
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK, pageFile.file().write(0, encoded, io));
    IndexedPageSet pages = new IndexedPageSet(
        pageFile.file(), stagingFile.file(), DATABASE, GENERATION, LARGE_TEST_CACHE);

    assertEquals(StatusCode.OK, pages.readCurrent(pageFile.file(), 1, 0, io));
    assertEquals(false, pages.isPresent(1));
    assertEquals(PageCodec.PAYLOAD_KIND_TUPLE_BTREE, pages.payloadKind(1));
    assertEquals(41, pages.ownerKeyId(1));
    assertEquals(0, pages.activePageMetadataCount());

    assertEquals(StatusCode.OK, pageFile.file().close());
    assertEquals(StatusCode.OK, stagingFile.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rollsBackFailedCurrentLoadBeforeRetry(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageOperation = new DirectoryOperationResult();
    DirectoryOperationResult stagingOperation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageOperation));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingOperation));
    ByteBuffer encoded = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
    assertEquals(StatusCode.OK, PageCodec.encode(
        DATABASE, GENERATION, 1, 1, 1, 2,
        PageCodec.PAYLOAD_KIND_SCALAR_BTREE, PageCodec.SCALAR_OWNER_KEY_ID,
        0, encoded, new CRC32C()));
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK, pageOperation.file().write(0, encoded, io));
    OneShotReadFailureFile pagesFile = new OneShotReadFailureFile(pageOperation.file());
    IndexedPageSet pages = new IndexedPageSet(
        pagesFile, stagingOperation.file(), DATABASE, GENERATION, LARGE_TEST_CACHE);
    pages.installPresent(1);

    assertNull(pages.stageExisting(1, IndexedTableLimits.MAX_CHANGED_PAGES));
    assertEquals(StatusCode.IO_FAILURE, pages.lastStatus());
    assertEquals(0, pages.changedPageCount());
    assertEquals(false, pages.isStaged(1));
    assertNotNull(pages.stageExisting(1, IndexedTableLimits.MAX_CHANGED_PAGES));
    assertEquals(1, pages.changedPageCount());

    assertEquals(StatusCode.OK, pagesFile.close());
    assertEquals(StatusCode.OK, stagingOperation.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void scalarLookupPreservesPageLoadFailureStatus(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageOperation = new DirectoryOperationResult();
    DirectoryOperationResult stagingOperation = new DirectoryOperationResult();
    DirectoryOperationResult rowOperation = new DirectoryOperationResult();
    DirectoryOperationResult versionOperation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageOperation));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingOperation));
    assertEquals(StatusCode.OK, directory.createFile("rows", rowOperation));
    assertEquals(StatusCode.OK, directory.createFile("versions", versionOperation));

    OneShotReadFailureFile failingPages = new OneShotReadFailureFile(pageOperation.file());
    DatabaseResourceGovernor governor =
        DatabasePageCacheTestPlan.governor(LARGE_TEST_CACHE, 1);
    IndexedPageSet ioPages = new IndexedPageSet(
        failingPages, stagingOperation.file(), DATABASE, GENERATION, LARGE_TEST_CACHE);
    ioPages.installPresent(IndexedTableKernel.ROOT_META_PAGE_ID);
    IndexedRelationalScalarLookup ioLookup = new IndexedRelationalScalarLookup(
        ioPages.createKernel(rowOperation.file(), versionOperation.file(), governor.plan()),
        ioPages);
    assertEquals(StatusCode.IO_FAILURE, ioLookup.find(CatalogKeyspace.INDEX_ROOT_SPACE, 1));

    IndexedPageSet exhaustedPages = new IndexedPageSet(
        null, stagingOperation.file(), DATABASE, GENERATION, LARGE_TEST_CACHE);
    exhaustedPages.installPresent(IndexedTableKernel.ROOT_META_PAGE_ID);
    IndexedRelationalScalarLookup exhaustedLookup = new IndexedRelationalScalarLookup(
        exhaustedPages.createKernel(
            rowOperation.file(), versionOperation.file(), governor.plan()),
        exhaustedPages);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        exhaustedLookup.find(CatalogKeyspace.INDEX_ROOT_SPACE, 1));

    assertEquals(StatusCode.OK, failingPages.close());
    assertEquals(StatusCode.OK, stagingOperation.file().close());
    assertEquals(StatusCode.OK, rowOperation.file().close());
    assertEquals(StatusCode.OK, versionOperation.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void discardsFailedStagedLoadFrameBeforeRetry(@TempDir Path root) {
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult pageOperation = new DirectoryOperationResult();
    DirectoryOperationResult stagingOperation = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.createFile("pages", pageOperation));
    assertEquals(StatusCode.OK, directory.createFile("staging", stagingOperation));
    OneShotReadFailureFile stagingFile =
        new OneShotReadFailureFile(stagingOperation.file(), false);
    io.riverdb.engine.runtime.DatabasePageCachePlan config =
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(2, 1, 2);
    IndexedPageSet pages = new IndexedPageSet(
        pageOperation.file(), stagingFile, DATABASE, GENERATION, config);
    for (int pageId = 1; pageId <= 2; pageId++) {
      assertNotNull(pages.stageNew(
          pageId, IndexedTableLimits.MAX_PAGES,
          PageCodec.PAYLOAD_KIND_TUPLE_BTREE, 1));
    }
    stagingFile.failNextRead();

    assertNull(pages.stageNew(
        1, IndexedTableLimits.MAX_PAGES, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, 1));
    assertEquals(StatusCode.IO_FAILURE, pages.lastStatus());
    assertNotNull(pages.stageNew(
        1, IndexedTableLimits.MAX_PAGES, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, 1));

    assertEquals(StatusCode.OK, pageOperation.file().close());
    assertEquals(StatusCode.OK, stagingFile.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static void publishPrepared(
      IndexedPageSet pages,
      long oldestVisibleCommitSequence,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    assertEquals(StatusCode.OK, pages.beginPreparedBatch());
    assertEquals(
        StatusCode.OK,
        pages.freezeChangedPages(0, oldestVisibleCommitSequence));
    assertEquals(
        StatusCode.OK,
        pages.installPreparedPages(
            new long[] {commitSequence}, 1, recordStart, recordEnd));
    assertEquals(StatusCode.OK, pages.releasePreparedBatch());
  }

  private static void publishPrepared(
      IndexedPageFrameCache cache,
      long commitSequence,
      long recordStart,
      long recordEnd) {
    assertEquals(StatusCode.OK, cache.beginPreparedBatch());
    assertEquals(StatusCode.OK, cache.freezeChangedPages(0, commitSequence));
    assertEquals(StatusCode.OK,
        cache.installPreparedPages(
            new long[] {commitSequence}, 1, recordStart, recordEnd));
    assertEquals(StatusCode.OK, cache.releasePreparedBatch());
  }

  private static int allocatedCurrentFrames(IndexedPageFrameCache cache) {
    int count = 0;
    for (IndexedPageFrame frame : cache.currentFrames) {
      if (frame != null) count++;
    }
    return count;
  }

  private static final class OneShotReadFailureFile implements DurableFile {
    private final DurableFile delegate;
    private boolean failRead;

    private OneShotReadFailureFile(DurableFile file) {
      this(file, true);
    }

    private OneShotReadFailureFile(DurableFile file, boolean failInitially) {
      delegate = file;
      failRead = failInitially;
    }

    void failNextRead() { failRead = true; }

    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      if (failRead) {
        failRead = false;
        result.reset();
        return StatusCode.IO_FAILURE;
      }
      return delegate.read(position, target, result);
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      return delegate.write(position, source, result);
    }

    @Override
    public StatusCode force(ForceMode mode) { return delegate.force(mode); }

    @Override
    public StatusCode truncate(long sizeBytes) { return delegate.truncate(sizeBytes); }

    @Override
    public StatusCode size(FileSizeResult result) { return delegate.size(result); }

    @Override
    public StatusCode close() { return delegate.close(); }
  }

  private static final class WriteCountingFile implements DurableFile {
    private final DurableFile delegate;
    private int writes;

    private WriteCountingFile(DurableFile file) {
      delegate = file;
    }

    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      return delegate.read(position, target, result);
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      writes++;
      return delegate.write(position, source, result);
    }

    @Override
    public StatusCode force(ForceMode mode) { return delegate.force(mode); }

    @Override
    public StatusCode truncate(long sizeBytes) { return delegate.truncate(sizeBytes); }

    @Override
    public StatusCode size(FileSizeResult result) { return delegate.size(result); }

    @Override
    public StatusCode close() { return delegate.close(); }
  }

}
