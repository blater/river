package io.riverdb.journal.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.journal.api.mapping.DurableWalEnd;
import io.riverdb.journal.api.mapping.WalRecordRange;
import org.junit.jupiter.api.Test;

final class JournalUnitsTest {
  @Test
  void exclusiveDurableEndCoversOnlyCompleteSameLineageRanges() {
    DatabaseIncarnation database = DatabaseIncarnation.of(1, 2);
    WalRecordRange complete = new WalRecordRange(database, 3, 64, 96);
    DurableWalEnd exactEnd = new DurableWalEnd(database, 3, 96);
    DurableWalEnd shortEnd = new DurableWalEnd(database, 3, 95);
    DurableWalEnd otherGeneration = new DurableWalEnd(database, 4, 200);

    assertTrue(exactEnd.covers(complete));
    assertFalse(shortEnd.covers(complete));
    assertFalse(otherGeneration.covers(complete));
  }

  @Test
  void invalidLocalRangesAndNodeIncarnationsAreRejectedAtConstructionBoundary() {
    DatabaseIncarnation database = DatabaseIncarnation.of(1, 2);

    assertThrows(
        IllegalArgumentException.class,
        () -> new WalRecordRange(database, 3, 96, 96));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DurableWalEnd(database, 0, 96));
    assertThrows(IllegalArgumentException.class, () -> NodeIncarnation.of(0, 0));
  }
}
