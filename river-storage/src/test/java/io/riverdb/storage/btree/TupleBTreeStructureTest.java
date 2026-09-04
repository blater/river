package io.riverdb.storage.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class TupleBTreeStructureTest {
  @Test
  void derivesLevelsFromBinaryFanoutAndTheAllocatablePageDomain() {
    assertEquals(Integer.MAX_VALUE - 1, BTreeStructuralLimits.MAXIMUM_PAGE_ID);
    assertEquals(30, BTreeStructuralLimits.MAXIMUM_LEVELS);
    assertEquals(29, BTreeStructuralLimits.MAXIMUM_INTERNAL_LEVELS);

    assertEquals(0, BTreeStructuralLimits.maximumLevelsForPageCount(0));
    assertEquals(1, BTreeStructuralLimits.maximumLevelsForPageCount(1));
    assertEquals(3, BTreeStructuralLimits.maximumLevelsForPageCount(7));
    assertEquals(3, BTreeStructuralLimits.maximumLevelsForPageCount(8));
    assertEquals(30,
        BTreeStructuralLimits.maximumLevelsForPageCount(1_073_741_823));
    assertEquals(30,
        BTreeStructuralLimits.maximumLevelsForPageCount(BTreeStructuralLimits.MAXIMUM_PAGE_ID));
    assertEquals(31, BTreeStructuralLimits.maximumLevelsForPageCount(Integer.MAX_VALUE));
  }

  @Test
  void exposesExactTraversalAndPageIdBoundaries() {
    assertTrue(BTreeStructuralLimits.canVisitLevel(0));
    assertTrue(BTreeStructuralLimits.canVisitLevel(
        BTreeStructuralLimits.MAXIMUM_LEVELS - 1));
    assertFalse(BTreeStructuralLimits.canVisitLevel(BTreeStructuralLimits.MAXIMUM_LEVELS));
    assertTrue(BTreeStructuralLimits.canDescendFrom(
        BTreeStructuralLimits.MAXIMUM_INTERNAL_LEVELS - 1));
    assertFalse(BTreeStructuralLimits.canDescendFrom(
        BTreeStructuralLimits.MAXIMUM_INTERNAL_LEVELS));
    assertTrue(BTreeStructuralLimits.validPageId(BTreeStructuralLimits.MAXIMUM_PAGE_ID));
    assertFalse(BTreeStructuralLimits.validPageId(Integer.MAX_VALUE));
  }

  @Test
  void workspaceRequiresOneSlotPerStructurallyReachableLevel() {
    assertTrue(workspace(BTreeStructuralLimits.MAXIMUM_LEVELS).isValid());
    assertFalse(workspace(BTreeStructuralLimits.MAXIMUM_LEVELS - 1).isValid());
  }

  private static TupleBTreeTreeWorkspace workspace(int levels) {
    return new TupleBTreeTreeWorkspace(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES),
        ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES),
        new int[levels], new int[levels], new int[levels]);
  }
}
