package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlScanCursor;
import io.riverdb.engine.sql.SqlScanRowResult;
import io.riverdb.engine.sql.SqlSession;
import io.riverdb.engine.sql.SqlSessionOpenResult;
import java.nio.file.Path;

/** Entry point for the dependency-clean embedded River API. */
public final class EmbeddedRiver {
  private EmbeddedRiver() {
  }

  public static StatusCode create(
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      DatabaseOpenResult result) {
    return open(
        directory,
        database,
        generation,
        maximumActiveTransactions,
        true,
        result);
  }

  public static StatusCode openExisting(
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      DatabaseOpenResult result) {
    return open(
        directory,
        database,
        generation,
        maximumActiveTransactions,
        false,
        result);
  }

  private static StatusCode open(
      Path directory,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      boolean create,
      DatabaseOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    StatusCode status = create
        ? RelationalDatabase.create(
            directory, database, generation, maximumActiveTransactions, opened)
        : RelationalDatabase.openExisting(
            directory, database, generation, maximumActiveTransactions, opened);
    if (!status.isOk()) {
      return status;
    }
    EngineDatabase engine = new EngineDatabase(opened.database());
    status = result.complete(engine);
    if (!status.isOk()) {
      engine.close();
    }
    return status;
  }

  private static final class EngineDatabase implements RiverDatabase {
    private final RelationalDatabase database;
    private int openSessions;
    private boolean closed;

    private EngineDatabase(RelationalDatabase relational) {
      database = relational;
    }

    @Override
    public synchronized StatusCode createSession(SessionOpenResult result) {
      if (result == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (closed) {
        return StatusCode.CLOSED;
      }
      SqlSessionOpenResult opened = new SqlSessionOpenResult();
      StatusCode status = SqlSession.create(database, opened);
      if (!status.isOk()) {
        return status;
      }
      status = result.complete(new EngineSession(this, opened.session()));
      if (status.isOk()) {
        openSessions++;
      }
      return status;
    }

    @Override
    public synchronized StatusCode close() {
      if (closed) {
        return StatusCode.CLOSED;
      }
      if (openSessions != 0) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = database.close();
      if (status.isOk()) {
        closed = true;
      }
      return status;
    }

    private synchronized void sessionClosed() {
      openSessions--;
    }
  }

  private static final class EngineSession implements RiverSession {
    private final EngineDatabase owner;
    private final SqlSession session;
    private final SqlExecutionResult execution = new SqlExecutionResult();
    private final SqlScanCursor scan = new SqlScanCursor();
    private final SqlScanRowResult scanRow = new SqlScanRowResult();
    private final long[] values = new long[CommandResult.MAXIMUM_COLUMNS];
    private final EngineQuery query = new EngineQuery();
    private boolean closed;

    private EngineSession(EngineDatabase database, SqlSession sqlSession) {
      owner = database;
      session = sqlSession;
    }

    @Override
    public StatusCode execute(String sql, CommandResult result) {
      if (result == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (closed) {
        return StatusCode.CLOSED;
      }
      StatusCode status = session.execute(sql, execution);
      return status.isOk() ? copyExecution(result) : status;
    }

    @Override
    public StatusCode beginQuery(String sql, QueryOpenResult result) {
      if (result == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.reset();
      if (closed) {
        return StatusCode.CLOSED;
      }
      if (query.active) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = scan.reset();
      if (status.isOk()) {
        status = session.beginScan(sql, scan);
      }
      if (status.isOk()) {
        query.active = true;
        status = result.complete(query);
      }
      return status;
    }

    @Override
    public StatusCode close() {
      if (closed) {
        return StatusCode.CLOSED;
      }
      StatusCode status = StatusCode.OK;
      if (query.active) {
        status = session.closeScan(scan, execution);
        if (status.isOk()) {
          query.active = false;
        }
      }
      if (status.isOk()) {
        status = session.close();
      }
      if (status.isOk()) {
        closed = true;
        owner.sessionClosed();
      }
      return status;
    }

    private StatusCode copyExecution(CommandResult result) {
      int columns = execution.columnCount();
      for (int index = 0; index < columns; index++) {
        values[index] = execution.valueAt(index);
      }
      return result.complete(
          execution.affectedRows(),
          execution.commitSequence(),
          execution.transactionActive(),
          execution.hasValue(),
          execution.key(),
          values,
          execution.nullMask(),
          execution.varcharMask(),
          columns);
    }

    private final class EngineQuery implements RiverQuery {
      private boolean active;

      @Override
      public StatusCode next(RowResult result) {
        if (result == null) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        result.reset();
        if (closed) {
          return StatusCode.CLOSED;
        }
        if (!active) {
          return StatusCode.CONFLICT;
        }
        StatusCode status = session.nextScan(scan, scanRow);
        if (status == StatusCode.CONFLICT && !scanRow.isAvailable()) {
          return StatusCode.OK;
        }
        if (!status.isOk()) {
          return status;
        }
        int columns = scanRow.columnCount();
        for (int index = 0; index < columns; index++) {
          values[index] = scanRow.valueAt(index);
        }
        return result.complete(
            scanRow.key(),
            values,
            scanRow.nullMask(),
            scanRow.varcharMask(),
            columns);
      }

      @Override
      public StatusCode close(CommandResult result) {
        if (result == null) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        result.reset();
        if (closed) {
          return StatusCode.CLOSED;
        }
        if (!active) {
          return StatusCode.CONFLICT;
        }
        StatusCode status = session.closeScan(scan, execution);
        if (status.isOk()) {
          active = false;
          status = copyExecution(result);
        }
        return status;
      }

      @Override
      public boolean isActive() {
        return active;
      }

      @Override
      public int columnCount() {
        return scan.projectedColumnCount();
      }

      @Override
      public CharSequence columnName(int index) {
        return session.scanColumnName(scan, index);
      }

      @Override
      public boolean columnIsVarchar(int index) {
        return session.scanColumnIsVarchar(scan, index);
      }

      @Override
      public long rowsReturned() {
        return scan.rowsReturned();
      }
    }
  }
}
