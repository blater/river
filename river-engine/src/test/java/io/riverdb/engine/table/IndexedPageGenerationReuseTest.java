package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.engine.runtime.DatabasePageCacheTestPlan;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class IndexedPageGenerationReuseTest {
  @Test
  void preflightReclamationReusesFramesBeforeUnusedCapacity() {
    IndexedPageFrameCache cache = cache(8);
    publish(cache, 1, 1, 0);
    IndexedPageFrame first = cache.currentFrame(1, false);
    publish(cache, 1, 2, 1);
    IndexedPageFrame second = cache.currentFrame(1, false);

    for (int sequence = 3; sequence <= 64; sequence++) {
      publish(cache, 1, sequence, sequence - 1);
      assertSame(sequence % 2 == 1 ? first : second, cache.currentFrame(1, false));
      assertEquals(sequence, cache.currentPayload(1).getInt(0));
      assertEquals(2, allocatedFrames(cache));
    }
    assertEquals(StatusCode.OK, cache.detach());
  }

  @Test
  void repeatedReclamationRetainsEveryEmptyFrameExactlyOnce() {
    IndexedPageFrameCache cache = cache(8);
    publish(cache, 1, 1, 0);
    IndexedPageFrame first = cache.currentFrame(1, false);
    publish(cache, 1, 2, 0);
    IndexedPageFrame second = cache.currentFrame(1, false);
    publish(cache, 1, 3, 0);
    assertEquals(StatusCode.OK, cache.reclaimHistorical(3));
    assertEquals(StatusCode.OK, cache.reclaimHistorical(3));

    publish(cache, 2, 4, 3);
    assertSame(second, cache.currentFrame(2, false));
    publish(cache, 3, 5, 4);
    assertSame(first, cache.currentFrame(3, false));
    assertEquals(3, allocatedFrames(cache));
    publish(cache, 4, 6, 5);
    assertEquals(4, allocatedFrames(cache));
    for (int pageId = 1; pageId <= 4; pageId++) {
      assertEquals(pageId + 2, cache.currentPayload(pageId).getInt(0));
    }
    assertEquals(StatusCode.OK, cache.detach());
  }

  @Test
  void visibleAndPinnedGenerationsSurviveUntilBothProtectionsEnd() {
    IndexedPageFrameCache cache = cache(8);
    publish(cache, 1, 1, 0);
    IndexedPageFrame first = cache.currentFrame(1, false);
    publish(cache, 1, 2, 1);
    publish(cache, 1, 3, 1);
    IndexedPageGenerationPin pin = new IndexedPageGenerationPin();
    assertEquals(StatusCode.OK, cache.pinPageAt(1, 1, pin));
    assertEquals(1, pin.payload().getInt(0));

    publish(cache, 1, 4, 3);
    assertEquals(3, allocatedFrames(cache));
    assertEquals(1, pin.payload().getInt(0));
    assertEquals(StatusCode.OK, cache.unpinPage(pin));
    assertEquals(StatusCode.OK, cache.reclaimHistorical(4));
    publish(cache, 2, 5, 4);
    publish(cache, 3, 6, 5);
    assertSame(first, cache.currentFrame(3, false));
    assertEquals(3, allocatedFrames(cache));
    assertEquals(StatusCode.OK, cache.detach());
  }

  @Test
  void preparedGenerationsAreNotReclaimedBeforePublicationRelease() {
    IndexedPageFrameCache cache = cache(4);
    publish(cache, 1, 1, 0);
    assertNotNull(cache.stageExisting(1, 1));
    assertEquals(StatusCode.OK, cache.beginPreparedBatch());
    assertEquals(StatusCode.OK, cache.freezeChangedPages(0, 1));
    IndexedPageFrame firstPrepared = cache.prepared.frame(1, cache.currentFrames);
    assertEquals(StatusCode.OK, cache.reclaimHistorical(Long.MAX_VALUE));
    assertNotNull(cache.stageExisting(1, 1));
    assertEquals(StatusCode.OK, cache.freezeChangedPages(1, 1));
    assertEquals(StatusCode.OK,
        cache.installPreparedPages(new long[] {2, 3}, 2, 2, 3));
    assertEquals(StatusCode.OK, cache.reclaimHistorical(Long.MAX_VALUE));
    assertEquals(1, firstPrepared.pageId);
    assertEquals(1, firstPrepared.pinCount);
    assertEquals(StatusCode.OK, cache.releasePreparedBatch());
    assertEquals(StatusCode.OK, cache.reclaimHistorical(3));
    publish(cache, 2, 4, 3);
    assertSame(firstPrepared, cache.currentFrame(2, false));
    assertEquals(3, allocatedFrames(cache));
    assertEquals(StatusCode.OK, cache.detach());
  }

  @Test
  void snapshotPressureReturnsResourceStatusAndRecoversAfterReclamation() {
    IndexedPageFrameCache cache = cache(2);
    publish(cache, 1, 1, 0);
    publish(cache, 1, 2, 1);
    assertEquals(StatusCode.OK, cache.reclaimHistorical(1));
    assertNotNull(cache.stageExisting(1, 1));
    assertEquals(StatusCode.OK, cache.beginPreparedBatch());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, cache.freezeChangedPages(0, 1));
    assertEquals(2, cache.currentPayload(1).getInt(0));
    publish(cache, 1, 3, 2);
    assertEquals(3, cache.currentPayload(1).getInt(0));
    assertEquals(2, allocatedFrames(cache));
    assertEquals(StatusCode.OK, cache.detach());
    assertEquals(-1, cache.reusableCurrentSlot(true, Long.MAX_VALUE));
  }

  private static IndexedPageFrameCache cache(int frames) {
    DatabasePageCachePlan plan = DatabasePageCacheTestPlan.geometry(frames, 1, 1);
    return new IndexedPageFrameCache(
        null, null, DatabaseIncarnation.of(1, 2), WalGeneration.of(1),
        new IndexedPageState(plan), plan);
  }

  private static void publish(
      IndexedPageFrameCache cache, int pageId, long sequence, long oldestVisible) {
    // Match commit preflight: reclaim before compilation and publication reservation.
    assertEquals(StatusCode.OK, cache.reclaimHistorical(oldestVisible));
    ByteBuffer staged = cache.state.present(pageId)
        ? cache.stageExisting(pageId, 1) : cache.stageNew(pageId, 1);
    assertNotNull(staged);
    staged.putInt(0, (int) sequence);
    assertEquals(StatusCode.OK, cache.beginPreparedBatch());
    assertEquals(StatusCode.OK, cache.freezeChangedPages(0, oldestVisible));
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
