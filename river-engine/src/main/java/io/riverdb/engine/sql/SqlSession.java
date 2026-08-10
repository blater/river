package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.engine.table.IndexedSavepoint;
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
  private final CheckpointResult checkpoint = new CheckpointResult();
  private final IndexedSavepoint statementSavepoint = new IndexedSavepoint();
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
  private final ByteBuffer selected = ByteBuffer.allocateDirect(Long.BYTES);
  private boolean transactionActive;
  private boolean scanActive;

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
    if (scanActive || command.type() == SqlCommandType.SCAN) {
      return StatusCode.CONFLICT;
    }
    if (command.type() == SqlCommandType.BEGIN) {
      if (transactionActive) {
        return StatusCode.CONFLICT;
      }
      status = session.begin(command.isSerializableTransaction()
          ? IsolationLevel.SERIALIZABLE : IsolationLevel.REPEATABLE_READ);
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
    if (command.type() == SqlCommandType.CREATE_UNIQUE_INDEX) {
      if (transactionActive) {
        return StatusCode.CONFLICT;
      }
      status = database.createUniqueValueIndex(
          command.indexName(), command.tableName());
      if (status.isOk()) {
        result.setUpdate(0, 0);
      }
      return status;
    }
    if (command.type() == SqlCommandType.CHECKPOINT) {
      if (transactionActive) {
        return StatusCode.CONFLICT;
      }
      status = database.checkpoint(checkpoint);
      if (status.isOk()) {
        result.setUpdate(0, checkpoint.commitSequence());
      }
      return status;
    }
    boolean implicit = !transactionActive;
    if (implicit) {
      status = session.begin(IsolationLevel.READ_COMMITTED);
    }
    boolean active = status.isOk() && implicit;
    boolean savepointActive = false;
    if (status.isOk() && !implicit) {
      status = session.createSavepoint(statementSavepoint);
      savepointActive = status.isOk();
    }
    if (status.isOk()) {
      status = session.resolveTable(command.tableName(), table);
    }
    if (status.isOk()) {
      status = executeDataCommand(result);
    }
    if (savepointActive) {
      StatusCode savepointStatus = status.isOk()
          ? StatusCode.OK : session.rollbackToSavepoint(statementSavepoint);
      StatusCode release = session.releaseSavepoint(statementSavepoint);
      if (!savepointStatus.isOk()) {
        status = savepointStatus;
      }
      if (!release.isOk()) {
        status = release;
      }
    }
    if (status.isOk() && implicit) {
      status = session.commit(outcome);
      active = false;
      if (status.isOk()) {
        if (isSelect()) {
          result.setRow(result.key(), selected.getLong(0), outcome.commitSequence());
        } else {
          result.setUpdate(1, outcome.commitSequence());
        }
      }
    } else if (status.isOk()) {
      if (isSelect()) {
        result.setRow(result.key(), selected.getLong(0), session.visibleCommitSequence());
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

  public StatusCode beginScan(String sql, SqlScanCursor cursor) {
    if (cursor == null || scanActive) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = parser.parse(sql, command);
    if (!status.isOk()
        || (command.type() != SqlCommandType.SCAN
            && command.type() != SqlCommandType.VALUE_SCAN)) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    boolean implicit = !transactionActive;
    if (implicit) {
      status = session.begin(IsolationLevel.READ_COMMITTED);
    }
    if (status.isOk()) {
      status = session.resolveTable(command.tableName(), table);
    }
    if (status.isOk()) {
      if (command.type() == SqlCommandType.VALUE_SCAN) {
        status = session.beginValueScan(
            table,
            command.scanLowerInclusive(),
            command.scanUpperExclusive(),
            cursor.relational());
      } else {
        status = command.isBoundedScan()
            ? session.beginScan(
                table,
                command.scanLowerInclusive(),
                command.scanUpperExclusive(),
                cursor.relational())
            : session.beginScan(table, cursor.relational());
      }
    }
    if (status.isOk()) {
      status = cursor.claim(
          this, implicit, command.type() == SqlCommandType.VALUE_SCAN);
    }
    if (status.isOk()) {
      scanActive = true;
      return StatusCode.OK;
    }
    if (implicit) {
      session.abort(outcome);
    }
    return status;
  }

  public StatusCode nextScan(SqlScanCursor cursor, SqlScanRowResult result) {
    if (cursor == null || !cursor.isOwnedBy(this) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status;
    if (cursor.valueIndex()) {
      status = session.nextValueScan(
          table, cursor.relational(), result.relational(), indexed);
    } else {
      status = session.nextScan(cursor.relational(), result.relational());
    }
    if (!status.isOk()) {
      return status;
    }
    if (cursor.valueIndex()) {
      if (indexed.row().length() != Long.BYTES) {
        return StatusCode.CORRUPTION;
      }
      status = indexed.row().copyTo(result.valueBytes());
      if (status.isOk()) {
        result.set(indexed.key(), result.valueBytes().getLong(0));
        cursor.rowReturned();
      }
      return status;
    }
    if (result.relational().row().length() != Long.BYTES) {
      return StatusCode.CORRUPTION;
    }
    status = result.relational().row().copyTo(result.valueBytes());
    if (status.isOk()) {
      result.set(result.relational().key(), result.valueBytes().getLong(0));
      cursor.rowReturned();
    }
    return status;
  }

  public StatusCode closeScan(SqlScanCursor cursor, SqlExecutionResult result) {
    if (cursor == null || !cursor.isOwnedBy(this) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = session.closeScan(cursor.relational());
    if (!status.isOk()) {
      return status;
    }
    boolean implicit = cursor.implicitTransaction();
    cursor.complete();
    scanActive = false;
    if (implicit) {
      status = session.commit(outcome);
      if (status.isOk()) {
        result.setTransaction(false, outcome.commitSequence());
      }
    } else {
      result.setTransaction(true, session.visibleCommitSequence());
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
          ? session.insertLong(table, command.key(), command.value(), row)
          : session.updateLong(table, command.key(), command.value(), row);
    }
    if (command.type() == SqlCommandType.DELETE) {
      return session.deleteLong(table, command.key());
    }
    if (command.type() == SqlCommandType.SELECT_BY_VALUE) {
      StatusCode status = session.fetchByUniqueValue(table, command.value(), indexed);
      selected.clear();
      if (status.isOk()) {
        status = indexed.row().length() == Long.BYTES
            ? indexed.row().copyTo(selected) : StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        result.setRow(indexed.key(), selected.getLong(0), 0);
      }
      return status;
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
      result.setRow(command.key(), selected.getLong(0), 0);
    }
    return status;
  }

  private boolean isSelect() {
    return command.type() == SqlCommandType.SELECT
        || command.type() == SqlCommandType.SELECT_BY_VALUE;
  }
}
