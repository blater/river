package io.riverdb.base.column;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ColumnSetTest {
  @Test
  void crossesEveryWordBoundaryAndReservesColumn255() {
    ColumnSet columns = new ColumnSet();
    int[] boundaries = {0, 63, 64, 127, 128, 191, 192, 254};
    for (int column : boundaries) {
      assertTrue(columns.add(column));
      assertTrue(columns.contains(column));
    }
    assertFalse(columns.add(255));
    assertFalse(columns.contains(255));
    assertFalse(columns.setWord(3, Long.MIN_VALUE));
    assertTrue(columns.isValidFor(255));
    assertFalse(columns.isValidFor(254));

    for (int column : boundaries) {
      assertTrue(columns.remove(column));
      assertFalse(columns.contains(column));
    }
    assertTrue(columns.isEmpty());
  }

  @Test
  void validatesUnusedBitsAndCopiesWithoutAliasing() {
    ColumnSet source = new ColumnSet();
    assertTrue(source.setWord(0, -1L));
    assertTrue(source.setWord(1, 3));
    assertTrue(source.isValidFor(66));
    assertFalse(source.isValidFor(65));

    ColumnSet copy = new ColumnSet();
    copy.copyFrom(source);
    assertEquals(-1L, copy.word(0));
    assertEquals(3, copy.word(1));
    source.reset();
    assertEquals(-1L, copy.word(0));
    assertTrue(source.isEmpty());
    assertFalse(copy.isValidFor(-1));
    assertFalse(copy.isValidFor(256));
  }
}
