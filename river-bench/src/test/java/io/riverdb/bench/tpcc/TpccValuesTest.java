package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void originalMarkerReplacesBytesWithinTheRequestedWidth() {
    TpccValues values = new TpccValues(91);
    for (int index = 0; index < 100; index++) {
      String value = values.originalData(26, 50, true);
      assertTrue(value.length() >= 26 && value.length() <= 50);
      assertTrue(value.contains("ORIGINAL"));
    }
  }
}
