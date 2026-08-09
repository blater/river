package io.riverdb.base.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IdentifierTest {
  @Test
  void compactIdsReserveZeroAndRejectNegativeValues() {
    assertFalse(DatabaseId.NONE.isValid());
    assertFalse(TablespaceId.NONE.isValid());
    assertFalse(RelationId.NONE.isValid());
    assertFalse(IndexId.NONE.isValid());
    assertFalse(ColumnId.NONE.isValid());
    assertFalse(TransactionId.NONE.isValid());
    assertFalse(CommitSequence.NONE.isValid());
    assertFalse(CheckpointId.NONE.isValid());

    assertEquals(7, RelationId.of(7).value());
    assertThrows(IllegalArgumentException.class, () -> RelationId.of(0));
    assertThrows(IllegalArgumentException.class, () -> new RelationId(-1));
    assertThrows(IllegalArgumentException.class, () -> TransactionId.of(0));
    assertThrows(IllegalArgumentException.class, () -> new TransactionId(-1));
  }

  @Test
  void lsnHasExplicitNoneAndBeforeFirstSentinels() {
    assertFalse(Lsn.NONE.isValid());
    assertTrue(Lsn.BEFORE_FIRST.isValid());
    assertTrue(Lsn.of(42).compareTo(Lsn.BEFORE_FIRST) > 0);
    assertThrows(IllegalArgumentException.class, () -> Lsn.of(-1));
    assertThrows(IllegalArgumentException.class, () -> new Lsn(-2));
  }

  @Test
  void pageAndRowIdsRequireAllocationGenerations() {
    PageId page = PageId.of(TablespaceId.of(3), 17, 2);
    RowId row = RowId.of(page, 4, 8);

    assertTrue(page.isValid());
    assertTrue(row.isValid());
    assertEquals(2, page.generation());
    assertEquals(8, row.slotGeneration());
    assertThrows(
        IllegalArgumentException.class,
        () -> PageId.of(TablespaceId.of(3), 17, 0));
    assertThrows(IllegalArgumentException.class, () -> RowId.of(page, 4, 0));
  }

  @Test
  void logicalPositionsCompareOnlyInsideOneHistoryGeneration() {
    DatabaseIncarnation incarnation = DatabaseIncarnation.of(10, 20);
    JournalPosition first = JournalPosition.of(incarnation, 3, 8);
    JournalPosition second = JournalPosition.of(incarnation, 3, 9);
    JournalPosition otherGeneration = JournalPosition.of(incarnation, 4, 1);

    assertTrue(first.isComparableTo(second));
    assertTrue(first.compareSequence(second) < 0);
    assertFalse(first.isComparableTo(otherGeneration));
    assertThrows(IllegalArgumentException.class, () -> first.compareSequence(otherGeneration));
  }

  @Test
  void manifestIdentityMustCoverTheSameHistory() {
    DatabaseIncarnation incarnation = DatabaseIncarnation.of(1, 2);
    JournalPosition position = JournalPosition.of(incarnation, 1, 100);
    CheckpointManifestId manifest =
        CheckpointManifestId.of(incarnation, position, 1, 33, 44);

    assertTrue(manifest.isValid());
    assertThrows(
        IllegalArgumentException.class,
        () -> CheckpointManifestId.of(
            DatabaseIncarnation.of(5, 6), position, 1, 33, 44));
  }
}
