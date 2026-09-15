package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DatabaseCommitPipelineRetainedLayoutTest {
  @Test
  void matchesExactEightArrayOwnerAndWorkerFormula() {
    assertEquals(4_400, DatabaseCommitPipelineRetainedLayout.retainedBytes(1));
    assertEquals(4_752, DatabaseCommitPipelineRetainedLayout.retainedBytes(8));
    assertEquals(-1, DatabaseCommitPipelineRetainedLayout.retainedBytes(0));
  }

  @Test
  void pageCacheChargesExactOwnerTokenAndNextSlotArrays() {
    assertEquals(48, DatabasePageCacheRetainedLayout.durabilityLinkBytes(1));
    assertEquals(128, DatabasePageCacheRetainedLayout.durabilityLinkBytes(8));
  }
}
