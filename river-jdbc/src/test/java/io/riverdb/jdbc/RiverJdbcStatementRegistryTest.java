package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RiverJdbcStatementRegistryTest {
  @Test
  void growsBeyondLegacyLiveStatementCapacityAndReusesClosedSlots() throws Exception {
    RiverJdbcStatementRegistry registry = new RiverJdbcStatementRegistry();
    RiverJdbcStatement[] statements = new RiverJdbcStatement[257];
    for (int index = 0; index < statements.length; index++) {
      statements[index] = new RiverJdbcStatement(null, null);
      registry.register(statements[index]);
    }
    RiverJdbcStatement additional = new RiverJdbcStatement(null, null);
    assertDoesNotThrow(() -> registry.register(additional));
    registry.unregister(statements[7]);
    assertDoesNotThrow(() -> registry.register(new RiverJdbcStatement(null, null)));
  }

  @Test
  void closesEveryRegisteredStatementAndLeavesRegistryReusable() throws Exception {
    RiverJdbcStatementRegistry registry = new RiverJdbcStatementRegistry();
    TrackingStatement first = new TrackingStatement();
    TrackingStatement second = new TrackingStatement();
    registry.register(first);
    registry.register(second);
    assertDoesNotThrow(() -> assertNull(registry.closeAll()));
    assertTrue(first.wasClosed);
    assertTrue(second.wasClosed);
    for (int index = 0; index < 257; index++) {
      assertDoesNotThrow(() -> registry.register(new TrackingStatement()));
    }
  }

  private static final class TrackingStatement extends RiverJdbcStatement {
    private boolean wasClosed;

    TrackingStatement() {
      super(null, null);
    }

    @Override
    public void close() {
      wasClosed = true;
    }
  }
}
