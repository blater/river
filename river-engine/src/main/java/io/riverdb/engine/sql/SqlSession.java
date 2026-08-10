package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlParser;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;

/** Executes the first SQL point-statement subset through real catalog and transactions. */
public final class SqlSession {
  private final RelationalDatabase database;
  private final RelationalSession session;
  private final SqlParser parser = new SqlParser();
  private final SqlCommand command = new SqlCommand();
  private final TableDefinition table = new TableDefinition();
  private final TransactionOutcome outcome = new TransactionOutcome();
  private final HeapRowResult fetched = new HeapRowResult();
  private final ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
  private final ByteBuffer selected = ByteBuffer.allocateDirect(Long.BYTES);
  private boolean transactionActive;

  private SqlSession(RelationalDatabase relational, RelationalSession relationalSession) {
    database = relational;
    session = relationalSession;
  }

  public static StatusCode create(
      RelationalDatabase database,
      SqlSessionOpenResult result) {
    if (database == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    StatusCode status = database.createSession(sessionResult);
    if (status.isOk()) {
      result.set(new SqlSession(database, sessionResult.session()));
    }
    return status;
  }

  public StatusCode execute(String sql, SqlExecutionResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (transactionActive) {
      result.setTransaction(true, session.visibleCommitSequence());
    }
    StatusCode status = parser.parse(sql, command);
    if (!status.isOk()) {
      return status;
    }
    if (command.type() == SqlCommandType.BEGIN) {
      if (transactionActive) {
        return StatusCode.CONFLICT;
      }
      status = session.begin(IsolationLevel.REPEATABLE_READ);
      if (status.isOk()) {
        transactionActive = true;
        result.setTransaction(true, 0);
      }
      return status;
    }
    if (command.type() == SqlCommandType.COMMIT) {
      if (!transactionActive) {
        return StatusCode.CONFLICT;
      }
      status = session.commit(outcome);
      transactionActive = false;
      if (status.isOk()) {
        result.setTransaction(false, outcome.commitSequence());
      }
      return status;
    }
    if (command.type() == SqlCommandType.ROLLBACK) {
      if (!transactionActive) {
        return StatusCode.CONFLICT;
      }
      status = session.abort(outcome);
      transactionActive = false;
      if (status.isOk()) {
        result.setTransaction(false, 0);
      }
      return status;
    }
    if (command.type() == SqlCommandType.CREATE_TABLE) {
      if (transactionActive) {
        return StatusCode.CONFLICT;
      }
      status = database.createTable(command.tableName(), table);
      if (status.isOk()) {
        result.setUpdate(0, 0);
      }
      return status;
    }
    boolean implicit = !transactionActive;
    if (implicit) {
      status = session.begin(IsolationLevel.READ_COMMITTED);
    }
    boolean active = status.isOk() && implicit;
    if (status.isOk()) {
      status = session.resolveTable(command.tableName(), table);
    }
    if (status.isOk()) {
      status = executeDataCommand(result);
    }
    if (status.isOk() && implicit) {
      status = session.commit(outcome);
      active = false;
      if (status.isOk()) {
        if (command.type() == SqlCommandType.SELECT) {
          result.setValue(selected.getLong(0), outcome.commitSequence());
        } else {
          result.setUpdate(1, outcome.commitSequence());
        }
      }
    } else if (status.isOk()) {
      if (command.type() == SqlCommandType.SELECT) {
        result.setValue(selected.getLong(0), session.visibleCommitSequence());
      } else {
        result.setUpdate(1, 0);
      }
      result.setTransaction(true, result.commitSequence());
    }
    if (!status.isOk() && active) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        return abort;
      }
    }
    return status;
  }

  private StatusCode executeDataCommand(SqlExecutionResult result) {
    if (command.type() == SqlCommandType.INSERT
        || command.type() == SqlCommandType.UPDATE) {
      row.clear();
      row.putLong(0, command.value());
      row.position(0);
      row.limit(Long.BYTES);
      return command.type() == SqlCommandType.INSERT
          ? session.insert(table, command.key(), row)
          : session.update(table, command.key(), row);
    }
    if (command.type() == SqlCommandType.DELETE) {
      return session.delete(table, command.key());
    }
    if (command.type() != SqlCommandType.SELECT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.fetch(table, command.key(), fetched);
    selected.clear();
    if (status.isOk()) {
      status = fetched.length() == Long.BYTES
          ? fetched.copyTo(selected) : StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      result.setValue(selected.getLong(0), 0);
    }
    return status;
  }
}
