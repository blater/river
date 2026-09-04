package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

final class TpccTerminalRunnerTest {
  @Test
  void preservesExecutionFailureWhenTerminalCleanupAlsoFails() {
    Exception execution = new Exception("execution");
    SQLException cleanup = new SQLException("cleanup");

    assertSame(execution, TpccTerminalRunner.combineFailure(execution, cleanup));
    assertSame(cleanup, execution.getSuppressed()[0]);
    assertSame(cleanup, TpccTerminalRunner.combineFailure(null, cleanup));
  }
}
