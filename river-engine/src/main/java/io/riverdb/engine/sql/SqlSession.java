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
  private final int[] updatedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private final long[] matchedKeys = new long[SqlCommand.MAXIMUM_INSERT_ROWS];
  private final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private final long[] projectedValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final RelationalScanCursor aggregateCursor = new RelationalScanCursor();
  private final RelationalScanResult aggregateRow = new RelationalScanResult();
  private final ByteBuffer row = ByteBuffer.allocateDirect(
      (TableSchema.MAXIMUM_COLUMNS - 1) * Long.BYTES);
  private boolean transactionActive;
  private boolean userSavepointActive;
  private boolean scanActive;
  private boolean closed;
  private int userSavepointNameLength;
  private int predicateColumn;
  private int updatedColumnCount;
  private int matchedRowCount;
  private int projectedColumnCount;

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
    if (closed) {
      return StatusCode.CLOSED;
    }
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
    if (command.type() == SqlCommandType.CREATE_UNIQUE_INDEX
        || command.type() == SqlCommandType.CREATE_INDEX) {
      boolean unique = command.type() == SqlCommandType.CREATE_UNIQUE_INDEX;
      if (!transactionActive) {
        status = unique
            ? database.createUniqueValueIndex(
                command.indexName(), command.tableName(), command.firstColumnName())
            : database.createValueIndex(
                command.indexName(), command.tableName(), command.firstColumnName());
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = session.createValueIndex(
            command.indexName(), command.tableName(), command.firstColumnName(), unique);
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
      if (!status.isOk()) {
        StatusCode cancel = session.cancelLockWait();
        if (!cancel.isOk()) {
          status = cancel;
        }
      }
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
          result.setCommitSequence(outcome.commitSequence());
        } else {
          result.setUpdate(affectedRows(), outcome.commitSequence());
        }
      }
    } else if (status.isOk()) {
      if (isSelect()) {
        result.setCommitSequence(session.visibleCommitSequence());
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
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (scanActive) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = parser.parse(sql, command);
    boolean equality = status.isOk() && command.type() == SqlCommandType.SELECT;
    if (!status.isOk()
        || command.type() != SqlCommandType.SCAN && !equality) {
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
      if (equality) {
        status = bindProjections();
        predicateColumn = table.findColumn(command.predicateColumnName());
        if (status.isOk() && predicateColumn < 0) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
        }
      } else {
        status = bindDataCommand();
      }
    }
    boolean bounded = equality || command.isBoundedScan();
    if (status.isOk()
        && !equality
        && bounded
        && command.scanUpperExclusive() <= command.scanLowerInclusive()) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean valueIndex = status.isOk()
        && predicateColumn > 0
        && table.hasIndexOn(predicateColumn);
    boolean filterRows = status.isOk()
        && bounded
        && predicateColumn > 0
        && !valueIndex;
    if (status.isOk()
        && equality
        && predicateColumn == 0
        && command.key() == Long.MAX_VALUE) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      if (valueIndex) {
        long lower = equality ? command.key() : command.scanLowerInclusive();
        long upper = equality ? command.key() + 1 : command.scanUpperExclusive();
        status = command.key() == Long.MAX_VALUE && equality
            ? StatusCode.INVALID_EXTERNAL_INPUT
            : session.beginValueScan(
                table,
                table.findColumn(command.predicateColumnName()),
                lower,
                upper,
                cursor.relational());
      } else {
        status = bounded && predicateColumn == 0
            ? session.beginScan(
                table,
                equality ? command.key() : command.scanLowerInclusive(),
                equality ? command.key() + 1 : command.scanUpperExclusive(),
                cursor.relational())
            : session.beginScan(table, cursor.relational());
      }
    }
    if (status.isOk()) {
      status = cursor.claim(
          this,
          implicit,
          valueIndex,
          filterRows ? predicateColumn : -1,
          equality ? command.key() : command.scanLowerInclusive(),
          equality ? 0 : command.scanUpperExclusive(),
          equality,
          projectedColumns,
          projectedColumnCount);
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
    if (cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (!cursor.isOwnedBy(this)) {
      return StatusCode.CONFLICT;
    }
    result.reset();
    StatusCode status = StatusCode.OK;
    while (status.isOk()) {
      long primaryKey;
      HeapRowResult source;
      if (cursor.valueIndex()) {
        status = session.nextValueScan(
            table, cursor.relational(), result.relational(), indexed);
        primaryKey = indexed.key();
        source = indexed.row();
      } else {
        status = session.nextScan(cursor.relational(), result.relational());
        primaryKey = result.relational().key();
        source = result.relational().row();
      }
      if (!status.isOk()) {
        return status;
      }
      status = copyRow(source);
      if (status.isOk() && cursor.filtersRows()) {
        long predicateValue = cursor.filterColumn() == 0
            ? primaryKey : row.getLong((cursor.filterColumn() - 1) * Long.BYTES);
        if (!cursor.matches(predicateValue)) {
          continue;
        }
      }
      if (status.isOk()) {
        projectCopiedScanRow(primaryKey, cursor, projectedValues);
        result.set(primaryKey, projectedValues, cursor.projectedColumnCount());
        cursor.rowReturned();
      }
      return status;
    }
    return status;
  }

  public CharSequence scanColumnName(SqlScanCursor cursor, int index) {
    if (cursor == null || !cursor.isOwnedBy(this)) {
      return null;
    }
    int column = cursor.projectedColumn(index);
    return column < 0 ? null : table.columnName(column);
  }

  public StatusCode closeScan(SqlScanCursor cursor, SqlExecutionResult result) {
    if (cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (!cursor.isOwnedBy(this)) {
      return StatusCode.CONFLICT;
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

  /** Closes this session, aborting any explicit transaction still owned by it. */
  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (scanActive) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = StatusCode.OK;
    if (transactionActive) {
      status = session.abort(outcome);
      if (status.isOk()) {
        transactionActive = false;
        clearUserSavepoint();
      }
    }
    if (status.isOk()) {
      closed = true;
    }
    return status;
  }

  private StatusCode executeDataCommand(SqlExecutionResult result) {
    matchedRowCount = 0;
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
      if (!command.isEqualityPredicate()
          || predicateColumn > 0 && !table.hasUniqueIndexOn(predicateColumn)) {
        StatusCode status = collectMatchedKeys();
        for (int index = 0; status.isOk() && index < matchedRowCount; index++) {
          status = updatePrimaryKey(matchedKeys[index]);
        }
        return status;
      }
      long primaryKey = command.key();
      StatusCode status = StatusCode.OK;
      if (predicateColumn > 0) {
        status = session.fetchByUniqueValue(
            table, predicateColumn, command.key(), indexed);
        primaryKey = indexed.key();
      }
      if (status.isOk()) {
        status = updatePrimaryKey(primaryKey);
        matchedRowCount = status.isOk() ? 1 : 0;
      }
      return status;
    }
    if (command.type() == SqlCommandType.DELETE) {
      if (!command.isEqualityPredicate()
          || predicateColumn > 0 && !table.hasUniqueIndexOn(predicateColumn)) {
        StatusCode status = collectMatchedKeys();
        for (int index = 0; status.isOk() && index < matchedRowCount; index++) {
          status = session.deleteLong(table, matchedKeys[index]);
        }
        return status;
      }
      long primaryKey = command.key();
      StatusCode status = StatusCode.OK;
      if (predicateColumn > 0) {
        status = session.fetchByUniqueValue(
            table, predicateColumn, command.key(), indexed);
        primaryKey = indexed.key();
      }
      if (status.isOk()) {
        status = session.deleteLong(table, primaryKey);
        matchedRowCount = status.isOk() ? 1 : 0;
      }
      return status;
    }
    if (command.type() == SqlCommandType.COUNT) {
      long count = 0;
      boolean predicate = command.hasPredicate();
      boolean equality = command.isEqualityPredicate();
      boolean indexed = predicate
          && predicateColumn > 0
          && table.hasIndexOn(predicateColumn);
      boolean boundedPrimaryKey = predicate && predicateColumn == 0;
      if (predicate
          && !equality
          && command.scanUpperExclusive() <= command.scanLowerInclusive()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if ((indexed || boundedPrimaryKey)
          && equality
          && command.key() == Long.MAX_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      long lower = equality ? command.key() : command.scanLowerInclusive();
      long upper = equality ? command.key() + 1 : command.scanUpperExclusive();
      StatusCode status = indexed
          ? session.beginValueScan(
              table, predicateColumn, lower, upper, aggregateCursor)
          : boundedPrimaryKey
              ? session.beginScan(table, lower, upper, aggregateCursor)
              : session.beginScan(table, aggregateCursor);
      boolean aggregateActive = status.isOk();
      while (status.isOk()) {
        HeapRowResult source;
        if (indexed) {
          status = session.nextValueScan(
              table, aggregateCursor, aggregateRow, this.indexed);
          source = this.indexed.row();
        } else {
          status = session.nextScan(aggregateCursor, aggregateRow);
          source = aggregateRow.row();
        }
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.OK;
          break;
        }
        if (status.isOk() && predicate && predicateColumn > 0 && !indexed) {
          status = copyRow(source);
        }
        if (status.isOk() && predicate && predicateColumn > 0 && !indexed) {
          long value = row.getLong((predicateColumn - 1) * Long.BYTES);
          boolean matches = equality
              ? value == command.key()
              : value >= command.scanLowerInclusive()
                  && value < command.scanUpperExclusive();
          if (!matches) {
            continue;
          }
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
        projectedValues[0] = count;
        result.setProjection(0, projectedValues, 1, 0);
      }
      return status;
    }
    if (command.type() != SqlCommandType.SELECT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int predicateColumn = table.findColumn(command.predicateColumnName());
    StatusCode status;
    long primaryKey;
    HeapRowResult source;
    if (predicateColumn == 0) {
      primaryKey = command.key();
      status = session.fetch(table, primaryKey, fetched);
      source = fetched;
    } else {
      status = session.fetchByUniqueValue(
          table, predicateColumn, command.key(), indexed);
      primaryKey = indexed.key();
      source = indexed.row();
    }
    if (status.isOk()) {
      status = projectRow(
          primaryKey, source, projectedColumns, projectedColumnCount, projectedValues);
    }
    if (status.isOk()) {
      result.setProjection(primaryKey, projectedValues, projectedColumnCount, 0);
    }
    return status;
  }

  private boolean isSelect() {
    return command.type() == SqlCommandType.SELECT
        || command.type() == SqlCommandType.COUNT;
  }

  private StatusCode bindDataCommand() {
    updatedColumnCount = 0;
    predicateColumn = -1;
    projectedColumnCount = 0;
    if (command.type() == SqlCommandType.COUNT) {
      if (!command.hasPredicate()) {
        return StatusCode.OK;
      }
      predicateColumn = table.findColumn(command.predicateColumnName());
      return predicateColumn >= 0
          ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.type() == SqlCommandType.INSERT) {
      return bindInsertColumns();
    }
    if (command.type() == SqlCommandType.SELECT) {
      StatusCode status = bindProjections();
      int predicate = table.findColumn(command.predicateColumnName());
      return status.isOk() && (predicate == 0 || table.hasUniqueIndexOn(predicate))
          ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.type() == SqlCommandType.SCAN) {
      StatusCode status = bindProjections();
      if (!status.isOk()) {
        return status;
      }
      if (!command.isBoundedScan()) {
        return StatusCode.OK;
      }
      int predicate = table.findColumn(command.predicateColumnName());
      predicateColumn = predicate;
      return predicate >= 0 ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.type() == SqlCommandType.UPDATE) {
      predicateColumn = table.findColumn(command.predicateColumnName());
      if (command.updateColumnCount() <= 0
          || command.updateColumnCount() != command.columnCount()
          || predicateColumn < 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int index = 0; index < command.updateColumnCount(); index++) {
        int column = table.findColumn(command.columnName(index));
        if (column <= 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        for (int prior = 0; prior < index; prior++) {
          if (updatedColumns[prior] == column) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
        }
        updatedColumns[index] = column;
      }
      updatedColumnCount = command.updateColumnCount();
      return StatusCode.OK;
    }
    if (command.type() == SqlCommandType.DELETE) {
      predicateColumn = table.findColumn(command.predicateColumnName());
      return predicateColumn >= 0 ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode bindProjections() {
    int count = command.isSelectAll() ? table.columnCount() : command.columnCount();
    if (count <= 0 || count > projectedColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < count; index++) {
      int column = command.isSelectAll()
          ? index : table.findColumn(command.columnName(index));
      if (column < 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int previous = 0; previous < index; previous++) {
        if (projectedColumns[previous] == column) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      }
      projectedColumns[index] = column;
    }
    projectedColumnCount = count;
    return StatusCode.OK;
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

  private StatusCode projectRow(
      long primaryKey,
      HeapRowResult source,
      int[] columns,
      int columnCount,
      long[] destination) {
    StatusCode status = copyRow(source);
    if (status.isOk()) {
      for (int index = 0; index < columnCount; index++) {
        int column = columns[index];
        destination[index] = column == 0
            ? primaryKey : row.getLong((column - 1) * Long.BYTES);
      }
    }
    return status;
  }

  private void projectCopiedScanRow(
      long primaryKey,
      SqlScanCursor cursor,
      long[] destination) {
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int column = cursor.projectedColumn(index);
      destination[index] = column == 0
          ? primaryKey : row.getLong((column - 1) * Long.BYTES);
    }
  }

  private int affectedRows() {
    return command.type() == SqlCommandType.INSERT
        ? command.insertRowCount() : matchedRowCount;
  }

  private StatusCode updatePrimaryKey(long primaryKey) {
    StatusCode status = session.fetch(table, primaryKey, fetched);
    if (status.isOk()) {
      status = copyRow(fetched);
    }
    if (status.isOk()) {
      for (int index = 0; index < updatedColumnCount; index++) {
        row.putLong(
            (updatedColumns[index] - 1) * Long.BYTES,
            command.updateValue(index));
      }
      status = session.updateRow(table, primaryKey, row);
    }
    return status;
  }

  private StatusCode collectMatchedKeys() {
    matchedRowCount = 0;
    boolean equality = command.isEqualityPredicate();
    boolean indexed = predicateColumn > 0 && table.hasIndexOn(predicateColumn);
    boolean primaryRange = predicateColumn == 0 && !equality;
    if (indexed && equality && command.key() == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!equality
        && command.scanUpperExclusive() <= command.scanLowerInclusive()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long lower = equality ? command.key() : command.scanLowerInclusive();
    long upper = equality ? command.key() + 1 : command.scanUpperExclusive();
    StatusCode status = indexed
        ? session.beginValueScan(
            table,
            predicateColumn,
            lower,
            upper,
            aggregateCursor)
        : primaryRange
            ? session.beginScan(table, lower, upper, aggregateCursor)
            : session.beginScan(table, aggregateCursor);
    boolean active = status.isOk();
    while (status.isOk()) {
      status = indexed
          ? session.nextValueScan(
              table, aggregateCursor, aggregateRow, this.indexed)
          : session.nextScan(aggregateCursor, aggregateRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk() && !indexed && predicateColumn > 0) {
        status = copyRow(aggregateRow.row());
      }
      if (status.isOk()
          && !indexed
          && predicateColumn > 0) {
        long value = row.getLong((predicateColumn - 1) * Long.BYTES);
        boolean matches = equality
            ? value == command.key()
            : value >= command.scanLowerInclusive()
                && value < command.scanUpperExclusive();
        if (!matches) {
          continue;
        }
      }
      if (status.isOk() && matchedRowCount >= matchedKeys.length) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      if (status.isOk()) {
        matchedKeys[matchedRowCount++] = indexed
            ? this.indexed.key() : aggregateRow.key();
      }
    }
    if (active) {
      StatusCode close = session.closeScan(aggregateCursor);
      if (status.isOk()) {
        status = close;
      }
    }
    aggregateCursor.reset();
    return status;
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
