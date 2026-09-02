package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DatabaseResourceDefaultsTest {
  @Test
  void ungovernedWriteDefaultFitsInsideTheAddressableCeiling() {
    assertEquals(384, DatabaseResourceDefaults.TRANSACTION_WRITE_ENTRIES);
    assertEquals(1_048_576, DatabaseResourceDefaults.MAXIMUM_TRANSACTION_WRITE_ENTRIES);
  }
}
