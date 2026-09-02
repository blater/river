package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

final class RiverJdbcStatementRegistryTest {
  @Test
  void admitsBoundedLiveStatementsAndReusesClosedSlots() throws Exception {
    RiverJdbcStatementRegistry registry = new RiverJdbcStatementRegistry();
    RiverJdbcStatement[] statements = new RiverJdbcStatement[
        RiverJdbcStatementRegistry.MAXIMUM_STATEMENTS];
    for (int index = 0; index < statements.length; index++) {
      statements[index] = new RiverJdbcStatement(null, null);
      registry.register(statements[index]);
    }
    RiverJdbcStatement rejected = new RiverJdbcStatement(null, null);
    assertThrows(SQLException.class, () -> registry.register(rejected));
    registry.unregister(statements[7]);
    assertDoesNotThrow(() -> registry.register(rejected));
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
    for (int index = 0; index < RiverJdbcStatementRegistry.MAXIMUM_STATEMENTS;
        index++) {
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
