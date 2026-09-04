package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class IndexedPageMetadataBoundTest {
  @Test
  void sameWorkingSetRetainsSameCardinalityAcrossDifferentPageIdHistories() {
    io.riverdb.engine.runtime.DatabasePageCachePlan config =
        io.riverdb.engine.runtime.DatabasePageCacheTestPlan.geometry(4, 2, 2);
    IndexedPageState shortHistory = new IndexedPageState(config);
    IndexedPageState longHistory = new IndexedPageState(config);

    assertEquals(StatusCode.OK, shortHistory.installPresent(4));
    for (int pageId = 1; pageId <= 100_000; pageId++) {
      assertEquals(StatusCode.OK, longHistory.installPresent(pageId));
    }
    assertEquals(0, shortHistory.metadataEntryCount());
    assertEquals(0, longHistory.metadataEntryCount());
    assertEquals(shortHistory.metadataCapacity(), longHistory.metadataCapacity());

    assertEquals(StatusCode.OK, shortHistory.addChangedPage(4, 2));
    assertEquals(StatusCode.OK, longHistory.addChangedPage(100_000, 2));
    assertEquals(StatusCode.OK, shortHistory.markChanged(4, 11, 19));
    assertEquals(StatusCode.OK, longHistory.markChanged(100_000, 11, 19));
    assertEquals(1, shortHistory.metadataEntryCount());
    assertEquals(1, longHistory.metadataEntryCount());
    assertEquals(shortHistory.metadataCapacity(), longHistory.metadataCapacity());
  }
}
