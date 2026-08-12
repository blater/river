package io.riverdb.journal.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.journal.api.durability.DurabilityOutcome;
import io.riverdb.journal.api.durability.DurabilityRequirement;
import io.riverdb.journal.api.durability.DurabilityResult;
import io.riverdb.journal.api.mapping.DurableWalEnd;
import io.riverdb.journal.api.mapping.JournalPositionMapping;
import io.riverdb.journal.api.mapping.WalRecordRange;
import org.junit.jupiter.api.Test;

final class JournalUnitsTest {
  @Test
  void exclusiveDurableEndCoversOnlyCompleteSameLineageRanges() {
    DatabaseIncarnation database = DatabaseIncarnation.of(1, 2);
    WalGeneration generation = WalGeneration.of(3);
    WalRecordRange complete = new WalRecordRange(database, generation, 64, 96);
    DurableWalEnd exactEnd = new DurableWalEnd(database, generation, 96);
    DurableWalEnd shortEnd = new DurableWalEnd(database, generation, 95);
    DurableWalEnd otherGeneration = new DurableWalEnd(database, WalGeneration.of(4), 200);
    DurableWalEnd otherDatabase = new DurableWalEnd(
        DatabaseIncarnation.of(9, 10), generation, 200);

    assertTrue(exactEnd.covers(complete));
    assertFalse(shortEnd.covers(complete));
    assertFalse(otherGeneration.covers(complete));
    assertFalse(otherDatabase.covers(complete));
  }

  @Test
  void invalidLocalRangesAndNodeIncarnationsAreRejectedAtConstructionBoundary() {
    DatabaseIncarnation database = DatabaseIncarnation.of(1, 2);

    assertThrows(
        IllegalArgumentException.class,
        () -> new WalRecordRange(database, WalGeneration.of(3), 96, 96));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DurableWalEnd(database, WalGeneration.NONE, 96));
    assertThrows(IllegalArgumentException.class, () -> NodeIncarnation.of(0, 0));
  }

  @Test
  void reusableCarriersExposeTypedWalGenerationWithoutCreatingReplacementValues() {
    WalGeneration generation = WalGeneration.of(17);
    JournalAppendResult append = new JournalAppendResult().set(
        1, 2, 3, 4, generation, 5, 6, false);
    JournalPositionMapping mapping = new JournalPositionMapping().set(
        1, 2, 3, 4, generation, 5, 6, 7, 8, true);
    DurabilityResult durability = new DurabilityResult().set(
        DurabilityOutcome.SATISFIED,
        DurabilityRequirement.LOCAL_DURABLE,
        1,
        1,
        2,
        3,
        4,
        generation,
        6);

    assertSame(generation, append.walGeneration());
    assertSame(generation, mapping.walGeneration());
    assertSame(generation, durability.walGeneration());
    assertSame(append, append.reset());
    assertSame(mapping, mapping.reset());
    assertSame(durability, durability.reset());
    assertEquals(WalGeneration.NONE, append.walGeneration());
    assertEquals(WalGeneration.NONE, mapping.walGeneration());
    assertEquals(WalGeneration.NONE, durability.walGeneration());
  }
}
