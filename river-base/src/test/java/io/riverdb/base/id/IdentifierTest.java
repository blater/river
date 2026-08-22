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
    assertFalse(RowVersionId.NONE.isValid());
    assertFalse(LogicalRowId.NONE.isValid());
    assertFalse(TransactionId.NONE.isValid());
    assertFalse(CommitSequence.NONE.isValid());
    assertFalse(CheckpointId.NONE.isValid());
    assertFalse(WalGeneration.NONE.isValid());

    assertEquals(Integer.MAX_VALUE, TablespaceId.of(Integer.MAX_VALUE).value());
    assertEquals(Integer.MAX_VALUE, RelationId.of(Integer.MAX_VALUE).value());
    assertEquals(Integer.MAX_VALUE, IndexId.of(Integer.MAX_VALUE).value());
    assertEquals(Integer.MAX_VALUE, ColumnId.of(Integer.MAX_VALUE).value());
    assertEquals(Long.MAX_VALUE, RowVersionId.of(Long.MAX_VALUE).value());
    assertEquals(Long.MAX_VALUE, LogicalRowId.of(Long.MAX_VALUE).value());
    assertEquals(Long.MAX_VALUE, DatabaseId.of(Long.MAX_VALUE).value());
    assertEquals(Long.MAX_VALUE, TransactionId.of(Long.MAX_VALUE).value());
    assertEquals(Long.MAX_VALUE, CommitSequence.of(Long.MAX_VALUE).value());
    assertEquals(Long.MAX_VALUE, CheckpointId.of(Long.MAX_VALUE).value());
    assertEquals(Long.MAX_VALUE, WalGeneration.of(Long.MAX_VALUE).value());

    assertThrows(IllegalArgumentException.class, () -> TablespaceId.of(0));
    assertThrows(IllegalArgumentException.class, () -> RelationId.of(0));
    assertThrows(IllegalArgumentException.class, () -> IndexId.of(0));
    assertThrows(IllegalArgumentException.class, () -> ColumnId.of(0));
    assertThrows(IllegalArgumentException.class, () -> RowVersionId.of(0));
    assertThrows(IllegalArgumentException.class, () -> LogicalRowId.of(0));
    assertThrows(IllegalArgumentException.class, () -> DatabaseId.of(0));
    assertThrows(IllegalArgumentException.class, () -> TransactionId.of(0));
    assertThrows(IllegalArgumentException.class, () -> CommitSequence.of(0));
    assertThrows(IllegalArgumentException.class, () -> CheckpointId.of(0));
    assertThrows(IllegalArgumentException.class, () -> WalGeneration.of(0));

    assertThrows(IllegalArgumentException.class, () -> new TablespaceId(-1));
    assertThrows(IllegalArgumentException.class, () -> new RelationId(-1));
    assertThrows(IllegalArgumentException.class, () -> new IndexId(-1));
    assertThrows(IllegalArgumentException.class, () -> new ColumnId(-1));
    assertThrows(IllegalArgumentException.class, () -> new RowVersionId(-1));
    assertThrows(IllegalArgumentException.class, () -> new LogicalRowId(-1));
    assertThrows(IllegalArgumentException.class, () -> new DatabaseId(-1));
    assertThrows(IllegalArgumentException.class, () -> new TransactionId(-1));
    assertThrows(IllegalArgumentException.class, () -> new CommitSequence(-1));
    assertThrows(IllegalArgumentException.class, () -> new CheckpointId(-1));
    assertThrows(IllegalArgumentException.class, () -> new WalGeneration(-1));
  }

  @Test
  void opaque128BitIdsReserveOnlyTheAllZeroValue() {
    assertFalse(DatabaseIncarnation.NONE.isValid());
    assertFalse(RequestId.NONE.isValid());
    assertFalse(IdempotencyKey.NONE.isValid());
    assertTrue(DatabaseIncarnation.of(0, 1).isValid());
    assertTrue(RequestId.of(Long.MIN_VALUE, Long.MAX_VALUE).isValid());
    assertTrue(IdempotencyKey.of(1, 0).isValid());

    assertThrows(IllegalArgumentException.class, () -> DatabaseIncarnation.of(0, 0));
    assertThrows(IllegalArgumentException.class, () -> RequestId.of(0, 0));
    assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.of(0, 0));
  }

  @Test
  void semanticUnitsHaveIndependentEqualityDomains() {
    WalGeneration walGeneration = WalGeneration.of(7);
    CheckpointId checkpoint = CheckpointId.of(7);

    assertEquals(WalGeneration.of(7), walGeneration);
    assertFalse(walGeneration.equals(checkpoint));
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
    assertFalse(PageId.NONE.isValid());
    assertFalse(RowId.NONE.isValid());
    PageId page = PageId.of(TablespaceId.of(3), 17, 2);
    RowId row = RowId.of(page, 4, 8);

    assertTrue(page.isValid());
    assertTrue(row.isValid());
    assertEquals(2, page.generation());
    assertEquals(8, row.slotGeneration());
    assertThrows(
        IllegalArgumentException.class,
        () -> PageId.of(TablespaceId.of(3), 17, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> PageId.of(TablespaceId.NONE, 17, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> PageId.of(TablespaceId.of(3), -1, 1));
    assertThrows(IllegalArgumentException.class, () -> RowId.of(page, 4, 0));
    assertThrows(IllegalArgumentException.class, () -> RowId.of(page, -1, 1));
  }

  @Test
  void logicalPositionsCompareOnlyInsideOneHistoryGeneration() {
    assertFalse(JournalPosition.NONE.isValid());
    DatabaseIncarnation incarnation = DatabaseIncarnation.of(10, 20);
    JournalPosition first = JournalPosition.of(incarnation, 3, 8);
    JournalPosition second = JournalPosition.of(incarnation, 3, 9);
    JournalPosition otherGeneration = JournalPosition.of(incarnation, 4, 1);

    assertTrue(first.isComparableTo(second));
    assertTrue(first.compareSequence(second) < 0);
    assertFalse(first.isComparableTo(otherGeneration));
    assertThrows(IllegalArgumentException.class, () -> first.compareSequence(otherGeneration));
    assertThrows(
        IllegalArgumentException.class,
        () -> JournalPosition.of(DatabaseIncarnation.NONE, 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> JournalPosition.of(incarnation, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> JournalPosition.of(incarnation, 1, -1));
  }

  @Test
  void manifestIdentityMustCoverTheSameHistory() {
    assertFalse(CheckpointManifestId.NONE.isValid());
    DatabaseIncarnation incarnation = DatabaseIncarnation.of(1, 2);
    JournalPosition position = JournalPosition.of(incarnation, 1, 100);
    CheckpointManifestId manifest =
        CheckpointManifestId.of(incarnation, position, 1, 33, 44);

    assertTrue(manifest.isValid());
    assertThrows(
        IllegalArgumentException.class,
        () -> CheckpointManifestId.of(
            DatabaseIncarnation.of(5, 6), position, 1, 33, 44));
    assertThrows(
        IllegalArgumentException.class,
        () -> CheckpointManifestId.of(incarnation, position, 0, 33, 44));
    assertThrows(
        IllegalArgumentException.class,
        () -> CheckpointManifestId.of(incarnation, position, 1, 0, 0));
  }
}
