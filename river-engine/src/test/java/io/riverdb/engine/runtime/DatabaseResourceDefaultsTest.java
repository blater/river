package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DatabaseResourceDefaultsTest {
  @Test
  void ungovernedWritesStopOnlyAtTheRepresentationBoundary() {
    assertEquals(
        Integer.MAX_VALUE,
        DatabaseResourceDefaults.ADDRESSABLE_TRANSACTION_WRITE_ENTRIES);
  }
}
