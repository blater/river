package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.runtime.DatabasePageCacheTestPlan;
import io.riverdb.format.btree.TupleRowOverflowCodec;
import io.riverdb.format.btree.TupleRowOverflowHeader;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileIoMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class IndexedOverflowRetirementTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(461, 463);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @ParameterizedTest
  @ValueSource(ints = {40, 400})
  void emptyQueueReadsOnlyMetadataAmongColdUnrelatedPages(int pageCount, @TempDir Path root) {
    try (Files files = new Files(root)) {
      for (int pageId = 1; pageId <= pageCount; pageId++) {
        ByteBuffer payload = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
        if (pageId == IndexedTableKernel.ROOT_META_PAGE_ID) {
          assertEquals(StatusCode.OK, BTreeRootPage.initialize(payload, 3, pageCount + 1));
        }
        files.write(pageId, PageCodec.PAYLOAD_KIND_SCALAR_BTREE, 0, payload);
      }
      IndexedPageSet pages = files.pages(4);
      assertEquals(StatusCode.OK, pages.installPresent(pageCount));
      IndexedRetiredOverflowReclaimer reclaim = new IndexedRetiredOverflowReclaimer(pages);
      IndexedRelationalMutation mutation = mutation();
      long before = files.counters.readCalls();
      assertEquals(StatusCode.OK, reclaim.reclaim(2, Long.MAX_VALUE, mutation, 0, 0));
      assertEquals(1, files.counters.readCalls() - before);
      before = files.counters.readCalls();
      assertEquals(StatusCode.OK, reclaim.reclaim(2, Long.MAX_VALUE, mutation, 0, 0));
      assertEquals(0, files.counters.readCalls() - before);
      assertEquals(0, reclaim.count());
    }
  }

  @Test
  void multipleCrossOwnerPagesEnableAllocationAtAddressLimitAndAbortRestoresThem(
      @TempDir Path root) {
    try (Files files = new Files(root)) {
      files.seedRetiredPair();
      IndexedPageSet pages = files.pages(16);
      assertEquals(StatusCode.OK, pages.installPresent(6));
      IndexedRetiredOverflowReclaimer reclaim = new IndexedRetiredOverflowReclaimer(pages);
      IndexedRelationalMutation mutation = mutation();
      assertFalse(BTreeRootPage.hasAllocations(metadata(pages), 2, IndexedTableLimits.MAX_PAGES));
      assertEquals(StatusCode.OK, reclaim.reclaim(2, 6, mutation, 0, 0));
      assertEquals(0, reclaim.count());
      assertEquals(2, BTreeRootPage.retiredOverflowCount(metadata(pages)));
      assertEquals(StatusCode.OK, reclaim.reclaim(2, Long.MAX_VALUE, mutation, 0, 0));
      assertEquals(2, reclaim.count());
      assertTrue(BTreeRootPage.hasAllocations(metadata(pages), 2, IndexedTableLimits.MAX_PAGES));
      IndexedOperationPage allocation = new IndexedOperationPage();
      IndexedOperationPage metadata = new IndexedOperationPage();
      assertEquals(StatusCode.OK, pages.pinScalarOperationPage(
          IndexedTableKernel.ROOT_META_PAGE_ID, true, metadata));
      for (int expected : new int[] {6, 5}) {
        assertEquals(StatusCode.OK,
            IndexedOperationPageAllocation.tupleOverflow(pages, metadata.payload(), 99, allocation));
        assertEquals(expected, allocation.pageId());
        assertEquals(2, allocation.durableGeneration());
        assertEquals(StatusCode.OK, pages.releaseOperationPage(allocation));
      }
      assertEquals(StatusCode.OK, pages.releaseOperationPage(metadata));
      assertEquals(0, BTreeRootPage.retiredOverflowCount(metadata(pages)));
      pages.clearStagedFlags();
      pages.resetChanges();
      assertEquals(2, BTreeRootPage.retiredOverflowCount(metadata(pages)));
      assertEquals(0, BTreeRootPage.freePageCount(metadata(pages)));
      assertEquals(41, pages.ownerKeyId(5));
      assertEquals(42, pages.ownerKeyId(6));
      mutation = mutation();
      assertEquals(StatusCode.OK, reclaim.reclaim(2, Long.MAX_VALUE, mutation, 0, 0));
      assertEquals(2, reclaim.count());
    }
  }

  @Test
  void stagedQueueLinkRetainsEligibilityButStagedRemovalDoesNot(@TempDir Path root) {
    try (Files files = new Files(root)) {
      files.seedRetiredPair();
      IndexedPageSet pages = files.pages(16);
      assertEquals(StatusCode.OK, pages.installPresent(6));
      IndexedOperationPage overflow = new IndexedOperationPage();
      assertEquals(StatusCode.OK, pages.pinNewTupleOverflowPage(7, 43, overflow));
      TupleRowOverflowHeader header = new TupleRowOverflowHeader();
      assertEquals(StatusCode.OK, TupleRowOverflowCodec.encode(
          overflow.payload(), 0, 3, ByteBuffer.wrap(new byte[] {3}), 0, 1));
      assertEquals(StatusCode.OK, TupleRowOverflowCodec.retire(overflow.payload(), 0, 3, 10, header));
      assertEquals(StatusCode.OK, pages.releaseOperationPage(overflow));
      // The address-limit fixture has room in its logical domain for page 7.
      assertEquals(StatusCode.OK, new IndexedOverflowRetirementQueue(pages).append(7));
      IndexedRetiredOverflowReclaimer reclaim = new IndexedRetiredOverflowReclaimer(pages);
      assertEquals(StatusCode.OK, reclaim.reclaim(3, Long.MAX_VALUE, mutation(), 0, 0));
      assertEquals(2, reclaim.count());
      assertEquals(7, BTreeRootPage.retiredOverflowHead(metadata(pages)));
      assertEquals(1, BTreeRootPage.retiredOverflowCount(metadata(pages)));
    }
  }

  @Test
  void successivePreparedMembersReuseDistinctCheckpointedQueueHeads(@TempDir Path root) {
    try (Files files = new Files(root)) {
      files.seedRetiredPair();
      IndexedPageSet pages = files.pages(16);
      assertEquals(StatusCode.OK, pages.installPresent(6));
      assertEquals(StatusCode.OK, pages.beginPreparedBatch());
      IndexedRetiredOverflowReclaimer reclaim = new IndexedRetiredOverflowReclaimer(pages);
      IndexedOperationPage allocation = new IndexedOperationPage();
      IndexedOperationPage metadata = new IndexedOperationPage();
      for (int member = 0; member < 2; member++) {
        assertEquals(StatusCode.OK, reclaim.reclaim(1, Long.MAX_VALUE, mutation(), 0, 0));
        assertEquals(1, reclaim.count());
        assertEquals(StatusCode.OK, pages.pinScalarOperationPage(
            IndexedTableKernel.ROOT_META_PAGE_ID, true, metadata));
        assertEquals(StatusCode.OK,
            IndexedOperationPageAllocation.tupleOverflow(pages, metadata.payload(), 99, allocation));
        assertEquals(5 + member, allocation.pageId());
        assertEquals(2, allocation.durableGeneration());
        assertEquals(StatusCode.OK, TupleRowOverflowCodec.encode(allocation.payload(), 0,
            10 + member, ByteBuffer.wrap(new byte[] {(byte) member}), 0, 1));
        assertEquals(StatusCode.OK, pages.releaseOperationPage(allocation));
        assertEquals(StatusCode.OK, pages.releaseOperationPage(metadata));
        assertEquals(StatusCode.OK, pages.freezeChangedPages(member, Long.MAX_VALUE));
        pages.resetChanges();
      }
      assertEquals(StatusCode.OK, pages.installPreparedPages(new long[] {10, 11}, 2, 3, 4));
      assertEquals(StatusCode.OK, pages.releasePreparedBatch());
      assertEquals(0, BTreeRootPage.retiredOverflowCount(metadata(pages)));
      for (int pageId = 5; pageId <= 6; pageId++) assertEquals(99, pages.ownerKeyId(pageId));
    }
  }

  @Test
  void droppingAnOwnerUnlinksItsRetiredPagesBeforeReidentification(@TempDir Path root) {
    try (Files files = new Files(root)) {
      files.seedRetiredPair();
      IndexedPageSet pages = files.pages(16);
      assertEquals(StatusCode.OK, pages.installPresent(6));
      IndexedOverflowRetirementQueue queue = new IndexedOverflowRetirementQueue(pages);
      assertEquals(StatusCode.OK, queue.removeForDrop(6));
      assertEquals(1, BTreeRootPage.retiredOverflowCount(metadata(pages)));
      assertEquals(5, BTreeRootPage.retiredOverflowTail(metadata(pages)));
      assertEquals(StatusCode.OK, queue.removeForDrop(5));
      assertEquals(0, BTreeRootPage.retiredOverflowCount(metadata(pages)));
      assertEquals(0, BTreeRootPage.retiredOverflowHead(metadata(pages)));
      assertEquals(0, BTreeRootPage.retiredOverflowTail(metadata(pages)));
      pages.clearStagedFlags();
      pages.resetChanges();
      assertEquals(2, BTreeRootPage.retiredOverflowCount(metadata(pages)));
    }
  }

  @Test
  void appendSpillsUnrelatedStagingWithoutReusingMetadataAndAbortRestoresQueue(@TempDir Path root) {
    try (Files files = new Files(root)) {
      files.seedRetiredPair();
      files.seedRetired(7, 43);
      IndexedPageSet pages = files.pages(4, 2);
      assertEquals(StatusCode.OK, pages.installPresent(7));
      IndexedOverflowRetirementQueue queue = new IndexedOverflowRetirementQueue(pages);
      for (int attempt = 0; attempt < 2; attempt++) {
        stageMetadataThenUnrelated(pages);
        assertEquals(StatusCode.OK, queue.append(7));
        assertQueue(pages, 5, 7, 3);
        assertNext(pages, 6, 7);
        abort(pages);
        assertQueue(pages, 5, 6, 2);
        assertNext(pages, 6, 0);
      }
    }
  }

  @Test
  void appendPressureReleasesMetadataAndPublishesNothingUntilRetry(@TempDir Path root) {
    try (Files files = new Files(root)) {
      files.seedRetiredPair();
      files.seedRetired(7, 43);
      IndexedPageSet pages = files.pages(4, 2);
      assertEquals(StatusCode.OK, pages.installPresent(7));
      stageMetadataThenUnrelated(pages);
      IndexedOperationPage unrelated = new IndexedOperationPage();
      assertEquals(StatusCode.OK, pages.pinScalarOperationPage(3, true, unrelated));
      IndexedOverflowRetirementQueue queue = new IndexedOverflowRetirementQueue(pages);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, queue.append(7));
      assertQueue(pages, 5, 6, 2);
      assertNext(pages, 6, 0);
      assertEquals(StatusCode.OK, pages.releaseOperationPage(unrelated));
      abort(pages);
      stageMetadataThenUnrelated(pages);
      assertEquals(StatusCode.OK, queue.append(7));
      assertQueue(pages, 5, 7, 3);
      assertNext(pages, 6, 7);
    }
  }

  @Test
  void dropUnlinkSpillsAndPressureAbortsBeforeFreeStackPublication(@TempDir Path root) {
    try (Files files = new Files(root)) {
      files.seedRetiredPair();
      IndexedPageSet pages = files.pages(4, 2);
      assertEquals(StatusCode.OK, pages.installPresent(6));
      stageMetadataThenUnrelated(pages);
      IndexedOperationPage unrelated = new IndexedOperationPage();
      assertEquals(StatusCode.OK, pages.pinScalarOperationPage(3, true, unrelated));
      IndexedTupleGraphReclaimer reclaim = new IndexedTupleGraphReclaimer(pages);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED,
          reclaim.reclaimBatch(42, 5, 7, 7));
      assertQueue(pages, 5, 6, 2);
      assertEquals(StatusCode.OK, pages.releaseOperationPage(unrelated));
      abort(pages);
      stageMetadataThenUnrelated(pages);
      assertEquals(StatusCode.OK, reclaim.reclaimBatch(42, 5, 7, 7));
      assertQueue(pages, 5, 5, 1);
      assertNext(pages, 5, 0);
      assertEquals(6, BTreeRootPage.freePageHead(metadata(pages)));
      assertEquals(1, BTreeRootPage.freePageCount(metadata(pages)));
      assertEquals(PageCodec.PAYLOAD_KIND_FREE, pages.payloadKind(6));
      abort(pages);
      assertQueue(pages, 5, 6, 2);
      assertNext(pages, 5, 6);
      assertEquals(0, BTreeRootPage.freePageCount(metadata(pages)));
      assertEquals(PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW, pages.payloadKind(6));
    }
  }

  @Test
  void reclamationSpillsAndExactReplayPreservesQueueAndFreeStack(@TempDir Path root) {
    try (Files files = new Files(root)) {
      files.seedRetiredPair();
      IndexedPageSet pages = files.pages(4, 2);
      assertEquals(StatusCode.OK, pages.installPresent(6));
      stageMetadataThenUnrelated(pages);
      IndexedRetiredOverflowReclaimer reclaim = new IndexedRetiredOverflowReclaimer(pages);
      assertEquals(StatusCode.OK, reclaim.reclaim(2, Long.MAX_VALUE, mutation(), 0, 0));
      assertEquals(2, reclaim.count());
      assertQueue(pages, 0, 0, 0);
      assertEquals(2, BTreeRootPage.freePageCount(metadata(pages)));
      assertEquals(6, BTreeRootPage.freePageHead(metadata(pages)));
      abort(pages);
      assertQueue(pages, 5, 6, 2);
      // Recovery repeats the durable identities against fresh, spill-limited frames.
      pages = files.pages(4, 2);
      assertEquals(StatusCode.OK, pages.installPresent(6));
      reclaim = new IndexedRetiredOverflowReclaimer(pages);
      ByteBuffer record = ByteBuffer.allocate(IndexedOverflowReclamationCodec.BYTES);
      for (int pageId = 5; pageId <= 6; pageId++) {
        stageMetadataThenUnrelated(pages);
        IndexedOverflowReclamationCodec.encode(record, pageId, pageId == 5 ? 6 : 0,
            pageId + 36, 1, 7);
        assertEquals(StatusCode.OK, reclaim.reclaimExact(record, 0));
      }
      assertQueue(pages, 0, 0, 0);
      assertEquals(2, BTreeRootPage.freePageCount(metadata(pages)));
      assertEquals(6, BTreeRootPage.freePageHead(metadata(pages)));
      assertEquals(5, io.riverdb.storage.btree.BTreeFreePage.nextPageId(pages.operationPayload(6)));
      assertEquals(0, io.riverdb.storage.btree.BTreeFreePage.nextPageId(pages.operationPayload(5)));
    }
  }

  private static void stageMetadataThenUnrelated(IndexedPageSet pages) {
    assertNotNull(pages.stageExisting(IndexedTableKernel.ROOT_META_PAGE_ID, 15));
    assertNotNull(pages.stageExisting(3, 15));
  }

  private static void assertQueue(IndexedPageSet pages, int head, int tail, int count) {
    ByteBuffer metadata = metadata(pages);
    assertEquals(StatusCode.OK, BTreeRootPage.validate(metadata));
    assertEquals(head, BTreeRootPage.retiredOverflowHead(metadata));
    assertEquals(tail, BTreeRootPage.retiredOverflowTail(metadata));
    assertEquals(count, BTreeRootPage.retiredOverflowCount(metadata));
  }

  private static void assertNext(IndexedPageSet pages, int pageId, int next) {
    TupleRowOverflowHeader header = new TupleRowOverflowHeader();
    assertEquals(StatusCode.OK, TupleRowOverflowCodec.validate(
        pages.operationPayload(pageId), 0, pageId - 4, header));
    assertEquals(next, header.nextRetiredPageId());
  }

  private static void abort(IndexedPageSet pages) {
    pages.clearStagedFlags();
    pages.resetChanges();
  }

  private static ByteBuffer metadata(IndexedPageSet pages) {
    return pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
  }

  private static IndexedRelationalMutation mutation() {
    IndexedRelationalMutation mutation = new IndexedRelationalMutation(4, 1, 1);
    assertEquals(StatusCode.OK, mutation.reserve(4, 1, 1, 128));
    int[] parts = {SqlTypeDescriptor.BIGINT};
    io.riverdb.base.tuple.TupleShape.Result shape = new io.riverdb.base.tuple.TupleShape.Result();
    assertEquals(StatusCode.OK, io.riverdb.base.tuple.TupleShape.create(parts, shape));
    assertEquals(StatusCode.OK, mutation.appendDescriptor(1, 99, 99,
        shape.value().descriptorHash(), parts, 0, 1));
    return mutation;
  }

  private static final class Files implements AutoCloseable {
    private final NioIoCounters counters = new NioIoCounters();
    private final NioDurableDirectory directory;
    private final DirectoryOperationResult pageFile = new DirectoryOperationResult();
    private final DirectoryOperationResult stagingFile = new DirectoryOperationResult();

    Files(Path root) {
      NioDirectoryOpenResult opened = new NioDirectoryOpenResult();
      assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
          root, new FatalStateFence(), counters, 8, opened));
      directory = opened.directory();
      assertEquals(StatusCode.OK, directory.createFile("pages", FileIoMode.POSITIONAL, pageFile));
      assertEquals(StatusCode.OK, directory.createFile("staging", FileIoMode.POSITIONAL, stagingFile));
    }

    IndexedPageSet pages(int frames) {
      return pages(frames, 16);
    }

    IndexedPageSet pages(int frames, int stagingFrames) {
      return new IndexedPageSet(pageFile.file(), stagingFile.file(), DATABASE, GENERATION,
          DatabasePageCacheTestPlan.geometry(frames, stagingFrames, 15));
    }

    void seedRetiredPair() {
      TupleRowOverflowHeader header = new TupleRowOverflowHeader();
      for (int pageId = 1; pageId <= 6; pageId++) {
        ByteBuffer payload = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
        int kind = PageCodec.PAYLOAD_KIND_SCALAR_BTREE;
        long owner = 0;
        if (pageId == IndexedTableKernel.ROOT_META_PAGE_ID) {
          assertEquals(StatusCode.OK,
              BTreeRootPage.initialize(payload, 3, IndexedTableLimits.MAX_PAGES + 1));
          BTreeRootPage.publishRetiredOverflow(payload, 5, 6, 2);
        } else if (pageId >= 5) {
          kind = PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW;
          owner = pageId + 36;
          assertEquals(StatusCode.OK, TupleRowOverflowCodec.encode(
              payload, 0, pageId - 4, ByteBuffer.wrap(new byte[] {1}), 0, 1));
          assertEquals(StatusCode.OK, TupleRowOverflowCodec.retire(payload, 0, pageId - 4, 7, header));
          if (pageId == 5) assertEquals(StatusCode.OK,
              TupleRowOverflowCodec.linkRetired(payload, 0, 6, header));
        }
        write(pageId, kind, owner, payload);
      }
    }

    void seedRetired(int pageId, long owner) {
      ByteBuffer payload = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
      TupleRowOverflowHeader header = new TupleRowOverflowHeader();
      assertEquals(StatusCode.OK, TupleRowOverflowCodec.encode(
          payload, 0, pageId - 4, ByteBuffer.wrap(new byte[] {1}), 0, 1));
      assertEquals(StatusCode.OK, TupleRowOverflowCodec.retire(payload, 0, pageId - 4, 7, header));
      write(pageId, PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW, owner, payload);
    }

    void write(int pageId, int kind, long owner, ByteBuffer payload) {
      ByteBuffer page = ByteBuffer.allocate(PageCodec.PAGE_BYTES);
      page.position(PageCodec.HEADER_BYTES);
      page.put(payload);
      page.clear();
      assertEquals(StatusCode.OK, PageCodec.encode(DATABASE, GENERATION, pageId, 1, 1, 2,
          kind, owner, PageCodec.MAX_PAYLOAD_BYTES, page, new CRC32C()));
      assertEquals(StatusCode.OK, pageFile.file().write(
          (long) (pageId - 1) * PageCodec.PAGE_BYTES, page, new IoResult()));
    }

    @Override
    public void close() {
      assertEquals(StatusCode.OK, pageFile.file().close());
      assertEquals(StatusCode.OK, stagingFile.file().close());
      assertEquals(StatusCode.OK, directory.close());
    }
  }
}
