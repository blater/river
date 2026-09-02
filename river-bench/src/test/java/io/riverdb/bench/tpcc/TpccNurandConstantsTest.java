package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class TpccNurandConstantsTest {
  @Test
  void separatesAndValidatesLoadAndRunConstants() {
    TpccNurandConstants constants = TpccNurandConstants.STANDARD;
    assertEquals(66, Math.abs(constants.runLast() - constants.loadLast()));
    assertThrows(IllegalArgumentException.class,
        () -> new TpccNurandConstants(100, 110, 259, 7_919));
    assertThrows(IllegalArgumentException.class,
        () -> new TpccNurandConstants(100, 196, 259, 7_919));
  }
}
