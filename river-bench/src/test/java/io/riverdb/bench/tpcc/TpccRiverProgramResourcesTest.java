package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.jdbc.RiverTransactionPrograms;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

final class TpccRiverProgramResourcesTest {
  @Test
  void retainsFailedClosesForRetryAndProtectsReferencedStatements() throws Exception {
    FailingPrograms programs = new FailingPrograms();
    TpccRiverProgramResources resources = new TpccRiverProgramResources(programs);
    resources.prepareStatement("first");
    long secondStatement = resources.prepareStatement("second");
    long firstProgram = resources.prepareProgram(new TransactionProgram(), "release first");
    resources.prepareProgram(new TransactionProgram(), "release second");
    programs.failProgramOnce = firstProgram;
    programs.failStatementOnce = secondStatement;

    assertThrows(SQLException.class, resources::close);
    assertEquals(2, programs.programCloseCalls);
    assertEquals(0, programs.statementCloseCalls);

    assertThrows(SQLException.class, resources::close);
    assertEquals(3, programs.programCloseCalls);
    assertEquals(2, programs.statementCloseCalls);

    resources.close();
    assertEquals(3, programs.programCloseCalls);
    assertEquals(3, programs.statementCloseCalls);
  }

  private static final class FailingPrograms implements RiverTransactionPrograms {
    private long nextHandle = 1;
    private long failProgramOnce;
    private long failStatementOnce;
    private int programCloseCalls;
    private int statementCloseCalls;

    @Override public long prepareStatement(String sql) { return nextHandle++; }
    @Override public long prepareProgram(TransactionProgram program) { return nextHandle++; }

    @Override
    public void executeProgram(
        long handle, TransactionProgramArguments arguments,
        TransactionProgramResult result) {
    }

    @Override
    public void closeProgram(long handle) throws SQLException {
      programCloseCalls++;
      if (handle == failProgramOnce) {
        failProgramOnce = 0;
        throw new SQLException("injected program close failure");
      }
    }

    @Override
    public void closeStatement(long handle) throws SQLException {
      statementCloseCalls++;
      if (handle == failStatementOnce) {
        failStatementOnce = 0;
        throw new SQLException("injected statement close failure");
      }
    }
  }
}
