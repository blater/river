package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TpccValuesTest {
  @Test
  void generatesDeterministicStandardDomains() {
    TpccValues first = new TpccValues(73);
    TpccValues second = new TpccValues(73);
    for (int index = 0; index < 100; index++) {
      assertEquals(first.number(1, 100_000), second.number(1, 100_000));
    }
    assertEquals("BARBARBAR", first.lastName(0));
    assertEquals("EINGEINGEING", first.lastName(999));
    int selected = first.nurand(1_023, 1, 3_000, 259);
    assertTrue(selected >= 1 && selected <= 3_000);
  }

  @Test
  void generatedValuesRespectWidthsAndOriginalMarkerBounds() {
    TpccValues values = new TpccValues(91);
    assertEquals(8, values.alpha(8, 8).length());
    assertEquals(16, values.alpha(16, 16).length());
    assertEquals(16, values.numeric(16).length());
    String minimumMarked = values.originalData(26, 26, true);
    assertEquals(26, minimumMarked.length());
    assertTrue(minimumMarked.contains("ORIGINAL"));
    String maximumMarked = values.originalData(50, 50, true);
    assertEquals(50, maximumMarked.length());
    assertTrue(maximumMarked.contains("ORIGINAL"));
    String minimumUnmarked = values.originalData(26, 26, false);
    assertEquals(26, minimumUnmarked.length());
    assertFalse(minimumUnmarked.contains("ORIGINAL"));

    for (int index = 0; index < 1_000; index++) {
      assertTrue(values.lastName(index).length() <= 16);
    }

    for (int index = 0; index < 64; index++) {
      String alpha = values.alpha(8, 16);
      assertTrue(alpha.length() >= 8);
      assertTrue(alpha.length() <= 16);
      String marked = values.originalData(26, 50, true);
      assertTrue(marked.length() >= 26 && marked.length() <= 50);
      assertTrue(marked.contains("ORIGINAL"));
      String unmarked = values.originalData(26, 50, false);
      assertTrue(unmarked.length() >= 26 && unmarked.length() <= 50);
      assertFalse(unmarked.contains("ORIGINAL"));
    }
  }
}
