package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.engine.runtime.DatabasePageCacheTestPlan;
import org.junit.jupiter.api.Test;

final class IndexedPageCacheConfigTest {
  @Test
  void compilesMoreThanTheFormerActiveStagingLimitFromBytes() {
    DatabasePageCachePlan.Result result = new DatabasePageCachePlan.Result();

    assertEquals(StatusCode.OK,
        DatabasePageCachePlan.compile(32_000_000, 4_000_000, 1_024, result));

    DatabasePageCachePlan config = result.plan();
    assertEquals(1_024, config.activeStagedPages());
    assertTrue(config.stagingFrames() > 127);
    assertTrue(config.currentFrames() > 0);
    assertTrue(config.maximumRetainedBytes() <= 32_000_000);
    assertTrue(config.stagingRetainedBytes() <= 4_000_000);
  }

  @Test
  void exactCompiledBudgetsReproduceTheSameGeometry() {
    DatabasePageCachePlan.Result first = new DatabasePageCachePlan.Result();
    assertEquals(StatusCode.OK,
        DatabasePageCachePlan.compile(12_000_000, 3_000_000, 300, first));
    DatabasePageCachePlan expected = first.plan();
    DatabasePageCachePlan.Result exact = new DatabasePageCachePlan.Result();

    assertEquals(StatusCode.OK,
        DatabasePageCachePlan.compile(
            expected.maximumRetainedBytes(),
            expected.stagingRetainedBytes(),
            expected.activeStagedPages(),
            exact));

    assertEquals(expected.currentFrames(), exact.plan().currentFrames());
    assertEquals(expected.stagingFrames(), exact.plan().stagingFrames());
    assertEquals(expected.activeMetadataEntries(), exact.plan().activeMetadataEntries());
  }

  @Test
  void rejectsMapAddressabilityAndArithmeticOverflowBeforeAllocation() {
    DatabasePageCachePlan.Result result = new DatabasePageCachePlan.Result();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        DatabasePageCachePlan.compile(
            Long.MAX_VALUE, Long.MAX_VALUE - 1, (1L << 29) + 1, result));
    assertNull(result.plan());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabasePageCachePlan.compile(Long.MAX_VALUE, Long.MAX_VALUE / 2, 1 << 29, result));
    assertNull(result.plan());
  }

  @Test
  void rejectsBudgetsThatCannotProvideStructuralProgressFrames() {
    DatabasePageCachePlan.Result result = new DatabasePageCachePlan.Result();

    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabasePageCachePlan.compile(100_000, 20_000, 1, result));
    assertNull(result.plan());
  }

  @Test
  void constructionRetainsGeometryButAllocatesPageFramesLazily() {
    DatabasePageCachePlan config = DatabasePageCacheTestPlan.geometry(4, 3, 8);
    IndexedPageState state = new IndexedPageState(config);
    IndexedPageFrameCache cache = new IndexedPageFrameCache(
        null, null, DatabaseIncarnation.of(1, 2), WalGeneration.of(1), state, config);

    for (IndexedPageFrame frame : cache.currentFrames) assertNull(frame);
    for (IndexedPageFrame frame : cache.stagingFrames) assertNull(frame);

    cache.frameAt(cache.currentFrames, 0);
    cache.frameAt(cache.stagingFrames, 0);
    assertEquals(StatusCode.OK, cache.detach());
    assertEquals(0, cache.currentFrames.length);
    assertEquals(0, cache.stagingFrames.length);
  }
}
