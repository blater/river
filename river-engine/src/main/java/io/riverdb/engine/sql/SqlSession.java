package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlIdentifier;
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
  private final TableSchema createSchema = new TableSchema();
  private final TransactionOutcome outcome = new TransactionOutcome();
  private final CheckpointResult checkpoint = new CheckpointResult();
  private final IndexedSavepoint statementSavepoint = new IndexedSavepoint();
  private final IndexedSavepoint userSavepoint = new IndexedSavepoint();
  private final char[] userSavepointName = new char[SqlIdentifier.MAXIMUM_LENGTH];
  private final int[] insertSourceByColumn = new int[TableSchema.MAXIMUM_COLUMNS];
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final RelationalScanCursor aggregateCursor = new RelationalScanCursor();
  private final RelationalScanResult aggregateRow = new RelationalScanResult();
  private final ByteBuffer row = ByteBuffer.allocateDirect(
      (TableSchema.MAXIMUM_COLUMNS - 1) * Long.BYTES);
  private final ByteBuffer selected = ByteBuffer.allocateDirect(Long.BYTES);
  private boolean transactionActive;
  private boolean userSavepointActive;
  private boolean scanActive;
  private int userSavepointNameLength;
  private int selectedColumn;

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
    if (scanActive
        || command.type() == SqlCommandType.SCAN
        || command.type() == SqlCommandType.VALUE_SCAN) {
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
    if (command.type() == SqlCommandType.SAVEPOINT) {
      if (!transactionActive) {
        return StatusCode.CONFLICT;
      }
      if (userSavepointActive) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = session.createSavepoint(userSavepoint);
      if (status.isOk()) {
        rememberUserSavepoint(command.savepointName());
        result.setTransaction(true, session.visibleCommitSequence());
      }
      return status;
    }
    if (command.type() == SqlCommandType.ROLLBACK_TO_SAVEPOINT) {
      if (!transactionActive || !matchesUserSavepoint(command.savepointName())) {
        return StatusCode.CONFLICT;
      }
      status = session.rollbackToSavepoint(userSavepoint);
      if (status.isOk()) {
        result.setTransaction(true, session.visibleCommitSequence());
      }
      return status;
    }
    if (command.type() == SqlCommandType.RELEASE_SAVEPOINT) {
      if (!transactionActive || !matchesUserSavepoint(command.savepointName())) {
        return StatusCode.CONFLICT;
      }
      status = session.releaseSavepoint(userSavepoint);
      if (status.isOk()) {
        clearUserSavepoint();
        result.setTransaction(true, session.visibleCommitSequence());
      }
      return status;
    }
    if (command.type() == SqlCommandType.COMMIT) {
      if (!transactionActive) {
        return StatusCode.CONFLICT;
      }
      status = session.commit(outcome);
      transactionActive = false;
      clearUserSavepoint();
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
      clearUserSavepoint();
      if (status.isOk()) {
        result.setTransaction(false, 0);
      }
      return status;
    }
    if (command.type() == SqlCommandType.CREATE_TABLE) {
      status = prepareCreateSchema();
      if (!status.isOk()) {
        return status;
      }
      if (!transactionActive) {
        status = database.createTable(
            command.tableName(), createSchema, table);
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = session.createTable(
            command.tableName(), createSchema, table);
      }
      if (!status.isOk() && statementSavepoint.isActive()) {
        StatusCode rollback = session.rollbackToSavepoint(statementSavepoint);
        if (!rollback.isOk()) {
          status = rollback;
        }
      }
      if (statementSavepoint.isActive()) {
        StatusCode release = session.releaseSavepoint(statementSavepoint);
        if (!release.isOk()) {
          status = release;
        }
      }
      if (status.isOk()) {
        result.setUpdate(0, 0);
        result.setTransaction(true, session.visibleCommitSequence());
      }
      return status;
    }
    if (command.type() == SqlCommandType.CREATE_UNIQUE_INDEX) {
      if (!transactionActive) {
        status = database.createUniqueValueIndex(
            command.indexName(), command.tableName(), command.firstColumnName());
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = session.createUniqueValueIndex(
            command.indexName(), command.tableName(), command.firstColumnName());
      }
      if (!status.isOk() && statementSavepoint.isActive()) {
        StatusCode rollback = session.rollbackToSavepoint(statementSavepoint);
        if (!rollback.isOk()) {
          status = rollback;
        }
      }
      if (statementSavepoint.isActive()) {
        StatusCode release = session.releaseSavepoint(statementSavepoint);
        if (!release.isOk()) {
          status = release;
        }
      }
      if (status.isOk()) {
        result.setUpdate(0, 0);
        result.setTransaction(true, session.visibleCommitSequence());
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
      status = bindDataCommand();
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
          result.setUpdate(affectedRows(), outcome.commitSequence());
        }
      }
    } else if (status.isOk()) {
      if (isSelect()) {
        result.setRow(result.key(), selected.getLong(0), session.visibleCommitSequence());
      } else {
        result.setUpdate(affectedRows(), 0);
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
      status = bindDataCommand();
    }
    boolean valueIndex = status.isOk() && scanUsesValueIndex();
    if (status.isOk()) {
      if (valueIndex) {
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
          this, implicit, valueIndex, selectedColumn);
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
      status = copyProjectedValue(
          indexed.row(), cursor.projectedColumn(), result.valueBytes());
      if (status.isOk()) {
        result.set(indexed.key(), result.valueBytes().getLong(0));
        cursor.rowReturned();
      }
      return status;
    }
    status = copyProjectedValue(
        result.relational().row(), cursor.projectedColumn(), result.valueBytes());
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
    if (command.type() == SqlCommandType.INSERT) {
      StatusCode status = StatusCode.OK;
      for (int index = 0; status.isOk() && index < command.insertRowCount(); index++) {
        encodeInsertRow(index);
        status = session.insertRow(
            table,
            command.insertValue(index, insertSourceByColumn[0]),
            row);
      }
      return status;
    }
    if (command.type() == SqlCommandType.UPDATE) {
      StatusCode status = session.fetch(table, command.key(), fetched);
      if (status.isOk()) {
        status = copyRow(fetched);
      }
      if (status.isOk()) {
        row.putLong((selectedColumn - 1) * Long.BYTES, command.value());
        status = session.updateRow(table, command.key(), row);
      }
      return status;
    }
    if (command.type() == SqlCommandType.DELETE) {
      return session.deleteLong(table, command.key());
    }
    if (command.type() == SqlCommandType.SELECT_BY_VALUE) {
      StatusCode status = session.fetchByUniqueValue(table, command.value(), indexed);
      selected.clear();
      if (status.isOk()) {
        status = copyProjectedValue(indexed.row(), selectedColumn, selected);
      }
      if (status.isOk()) {
        result.setRow(indexed.key(), selected.getLong(0), 0);
      }
      return status;
    }
    if (command.type() == SqlCommandType.COUNT) {
      long count = 0;
      StatusCode status = session.beginScan(table, aggregateCursor);
      boolean aggregateActive = status.isOk();
      while (status.isOk()) {
        status = session.nextScan(aggregateCursor, aggregateRow);
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.OK;
          break;
        }
        if (status.isOk()) {
          if (count == Long.MAX_VALUE) {
            status = StatusCode.RESOURCE_EXHAUSTED;
          } else {
            count++;
          }
        }
      }
      if (aggregateActive) {
        StatusCode close = session.closeScan(aggregateCursor);
        if (close.isOk()) {
          aggregateCursor.reset();
        }
        if (status.isOk()) {
          status = close;
        }
      }
      if (status.isOk()) {
        selected.clear();
        selected.putLong(0, count);
        result.setRow(0, count, 0);
      }
      return status;
    }
    if (command.type() != SqlCommandType.SELECT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.fetch(table, command.key(), fetched);
    selected.clear();
    if (status.isOk()) {
      status = copyProjectedValue(fetched, selectedColumn, selected);
    }
    if (status.isOk()) {
      result.setRow(command.key(), selected.getLong(0), 0);
    }
    return status;
  }

  private boolean isSelect() {
    return command.type() == SqlCommandType.SELECT
        || command.type() == SqlCommandType.SELECT_BY_VALUE
        || command.type() == SqlCommandType.COUNT;
  }

  private StatusCode bindDataCommand() {
    selectedColumn = -1;
    if (command.type() == SqlCommandType.COUNT) {
      return StatusCode.OK;
    }
    if (command.type() == SqlCommandType.INSERT) {
      return bindInsertColumns();
    }
    if (command.type() == SqlCommandType.SELECT) {
      selectedColumn = table.findColumn(command.firstColumnName());
      return selectedColumn > 0
              && table.findColumn(command.predicateColumnName()) == 0
          ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.type() == SqlCommandType.SELECT_BY_VALUE) {
      selectedColumn = table.findColumn(command.secondColumnName());
      return table.findColumn(command.firstColumnName()) == 0
              && selectedColumn > 0
              && table.findColumn(command.predicateColumnName()) == selectedColumn
              && table.hasUniqueIndexOn(selectedColumn)
          ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.type() == SqlCommandType.SCAN
        || command.type() == SqlCommandType.VALUE_SCAN) {
      selectedColumn = table.findColumn(command.secondColumnName());
      if (table.findColumn(command.firstColumnName()) != 0 || selectedColumn <= 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (!command.isBoundedScan()) {
        return StatusCode.OK;
      }
      int predicate = table.findColumn(command.predicateColumnName());
      return predicate == 0 || table.hasUniqueIndexOn(predicate)
          ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.type() == SqlCommandType.UPDATE) {
      selectedColumn = table.findColumn(command.firstColumnName());
      return selectedColumn > 0
              && table.findColumn(command.predicateColumnName()) == 0
          ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.type() == SqlCommandType.DELETE) {
      return table.matchesKeyColumn(command.predicateColumnName())
          ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private boolean scanUsesValueIndex() {
    int predicate = table.findColumn(command.predicateColumnName());
    return command.isBoundedScan() && table.hasUniqueIndexOn(predicate);
  }

  private StatusCode prepareCreateSchema() {
    createSchema.reset();
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < command.columnCount(); index++) {
      status = createSchema.addBigint(command.columnName(index));
    }
    return status;
  }

  private void encodeInsertRow(int rowIndex) {
    row.clear();
    row.limit(table.rowBytes());
    for (int column = 1; column < table.columnCount(); column++) {
      row.putLong(
          (column - 1) * Long.BYTES,
          command.insertValue(rowIndex, insertSourceByColumn[column]));
    }
    row.position(0);
  }

  private StatusCode bindInsertColumns() {
    if (command.insertColumnCount() != table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < insertSourceByColumn.length; index++) {
      insertSourceByColumn[index] = -1;
    }
    if (command.columnCount() == 0) {
      for (int index = 0; index < table.columnCount(); index++) {
        insertSourceByColumn[index] = index;
      }
      return StatusCode.OK;
    }
    if (command.columnCount() != table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int source = 0; source < command.columnCount(); source++) {
      int column = table.findColumn(command.columnName(source));
      if (column < 0 || insertSourceByColumn[column] >= 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      insertSourceByColumn[column] = source;
    }
    return StatusCode.OK;
  }

  private StatusCode copyRow(HeapRowResult source) {
    if (source.length() != table.rowBytes()) {
      return StatusCode.CORRUPTION;
    }
    row.clear();
    row.limit(table.rowBytes());
    StatusCode status = source.copyTo(row);
    if (status.isOk()) {
      row.position(0);
    }
    return status;
  }

  private StatusCode copyProjectedValue(
      HeapRowResult source,
      int column,
      ByteBuffer destination) {
    StatusCode status = copyRow(source);
    if (status.isOk()) {
      destination.clear();
      destination.putLong(0, row.getLong((column - 1) * Long.BYTES));
      destination.position(0);
      destination.limit(Long.BYTES);
    }
    return status;
  }

  private int affectedRows() {
    return command.type() == SqlCommandType.INSERT ? command.insertRowCount() : 1;
  }

  private void rememberUserSavepoint(CharSequence name) {
    userSavepointNameLength = name.length();
    for (int index = 0; index < userSavepointNameLength; index++) {
      userSavepointName[index] = name.charAt(index);
    }
    userSavepointActive = true;
  }

  private boolean matchesUserSavepoint(CharSequence name) {
    if (!userSavepointActive || name.length() != userSavepointNameLength) {
      return false;
    }
    for (int index = 0; index < userSavepointNameLength; index++) {
      if (name.charAt(index) != userSavepointName[index]) {
        return false;
      }
    }
    return true;
  }

  private void clearUserSavepoint() {
    for (int index = 0; index < userSavepointNameLength; index++) {
      userSavepointName[index] = 0;
    }
    userSavepointNameLength = 0;
    userSavepointActive = false;
  }
}
