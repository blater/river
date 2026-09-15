package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.engine.runtime.DatabasePageCacheTestPlan;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IndexedPreparedPageBatchTest {
  @Test
  void failedLaterMemberFreezePreservesPrefixAndCanRetryAfterReleasingPressure() {
    DatabasePageCachePlan plan = DatabasePageCacheTestPlan.geometry(5, 2, 2);
    IndexedPageFrameCache cache = new IndexedPageFrameCache(
        null, null, DatabaseIncarnation.of(1, 2), WalGeneration.of(1),
        new IndexedPageState(plan), plan);
    publish(cache, 1, 11, 1);
    publish(cache, 2, 22, 2);
    publish(cache, 3, 33, 3);
    cache.markClean(3);
    assertEquals(StatusCode.OK, cache.pinCurrentPage(3));

    ByteBuffer prefixStaging = cache.stageExisting(1, 2);
    assertNotNull(prefixStaging);
    prefixStaging.putInt(0, 111);
    assertEquals(StatusCode.OK, cache.beginPreparedBatch());
    assertEquals(StatusCode.OK, cache.freezeChangedPages(0, Long.MAX_VALUE));

    IndexedPageFrame predecessor = cache.currentFrame(1, false);
    IndexedPageFrame prefix = cache.prepared.frame(1, cache.currentFrames);
    assertNotNull(prefix);
    assertEquals(1, predecessor.pinCount);
    assertEquals(1, prefix.pinCount);
    assertEquals(4, allocatedFrames(cache));

    ByteBuffer rejectedPageOne = cache.stageExisting(1, 2);
    ByteBuffer rejectedPageTwo = cache.stageExisting(2, 2);
    assertNotNull(rejectedPageOne);
    assertNotNull(rejectedPageTwo);
    rejectedPageOne.putInt(0, 1111);
    rejectedPageTwo.putInt(0, 2222);

    // One physical frame remains: page one reserves it; staged page two and pinned page three
    // prevent a second reservation.
    assertEquals(StatusCode.RETRY,
        cache.freezeChangedPages(1, Long.MAX_VALUE));

    assertTrue(cache.prepared.active());
    assertSame(prefix, cache.prepared.frame(1, cache.currentFrames));
    assertEquals(1, prefix.pageId);
    assertEquals(111, prefix.payload.getInt(0));
    assertTrue(prefix.publicationReserved);
    assertEquals(1, prefix.pinCount);
    assertSame(predecessor, cache.currentFrame(1, false));
    assertEquals(1, predecessor.pinCount);
    assertNull(cache.prepared.frame(2, cache.currentFrames));

    // Discard only the rejected member's staging, retaining the prepared prefix.
    cache.clearStagedFlags();
    cache.state.resetChanges();
    assertSame(prefix, cache.prepared.frame(1, cache.currentFrames));
    assertEquals(1, prefix.pinCount);
    assertEquals(1, predecessor.pinCount);

    cache.unpinCurrentPage(3);
    ByteBuffer suffixPageOne = cache.stageExisting(1, 2);
    ByteBuffer suffixPageTwo = cache.stageExisting(2, 2);
    assertNotNull(suffixPageOne);
    assertNotNull(suffixPageTwo);
    suffixPageOne.putInt(0, 1111);
    suffixPageTwo.putInt(0, 2222);
    assertEquals(StatusCode.OK, cache.freezeChangedPages(1, Long.MAX_VALUE));
    assertEquals(StatusCode.OK,
        cache.installPreparedPages(new long[] {4, 5}, 2, 4, 6));

    IndexedPageGenerationPin prefixReader = new IndexedPageGenerationPin();
    IndexedPageGenerationPin pageOneReader = new IndexedPageGenerationPin();
    IndexedPageGenerationPin pageTwoReader = new IndexedPageGenerationPin();
    assertEquals(StatusCode.OK, cache.pinPageAt(1, 4, prefixReader));
    assertEquals(StatusCode.OK, cache.pinPageAt(1, 5, pageOneReader));
    assertEquals(StatusCode.OK, cache.pinPageAt(2, 5, pageTwoReader));
    assertEquals(111, prefixReader.payload().getInt(0));
    assertEquals(1111, pageOneReader.payload().getInt(0));
    assertEquals(2222, pageTwoReader.payload().getInt(0));
    assertEquals(StatusCode.OK, cache.releasePreparedBatch());
    assertEquals(StatusCode.OK, cache.unpinPage(prefixReader));
    assertEquals(StatusCode.OK, cache.unpinPage(pageOneReader));
    assertEquals(StatusCode.OK, cache.unpinPage(pageTwoReader));

    assertEquals(0, predecessor.pinCount);
    assertEquals(0, prefix.pinCount);
    assertFalse(prefix.publicationReserved);
    assertEquals(0, cache.currentFrame(1, false).pinCount);
    assertEquals(0, cache.currentFrame(2, false).pinCount);
    assertNull(cache.currentFrame(3, false));
    assertEquals(StatusCode.OK, cache.detach());
  }

  private static void publish(
      IndexedPageFrameCache cache, int pageId, int value, long sequence) {
    ByteBuffer staging = cache.state.present(pageId)
        ? cache.stageExisting(pageId, 2) : cache.stageNew(pageId, 2);
    assertNotNull(staging);
    staging.putInt(0, value);
    assertEquals(StatusCode.OK, cache.beginPreparedBatch());
    assertEquals(StatusCode.OK, cache.freezeChangedPages(0, Long.MAX_VALUE));
    assertEquals(StatusCode.OK,
        cache.installPreparedPages(new long[] {sequence}, 1, sequence, sequence + 1));
    assertEquals(StatusCode.OK, cache.releasePreparedBatch());
  }

  private static int allocatedFrames(IndexedPageFrameCache cache) {
    int count = 0;
    for (IndexedPageFrame frame : cache.currentFrames) {
      if (frame != null) count++;
    }
    return count;
  }
}
