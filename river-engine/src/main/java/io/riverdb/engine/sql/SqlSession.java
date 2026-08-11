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
  private static final String COUNT_COLUMN_NAME = "count";

  private final RelationalDatabase database;
  private final RelationalSession session;
  private final SqlParser parser = new SqlParser();
  private final SqlCommand command = new SqlCommand();
  private final SqlExecutionResult aggregateExecution = new SqlExecutionResult();
  private final TableDefinition table = new TableDefinition();
  private final TableDefinition joinTable = new TableDefinition();
  private final TableSchema createSchema = new TableSchema();
  private final TransactionOutcome outcome = new TransactionOutcome();
  private final CheckpointResult checkpoint = new CheckpointResult();
  private final IndexedSavepoint statementSavepoint = new IndexedSavepoint();
  private final IndexedSavepoint userSavepoint = new IndexedSavepoint();
  private final char[] userSavepointName = new char[SqlIdentifier.MAXIMUM_LENGTH];
  private final int[] insertSourceByColumn = new int[TableSchema.MAXIMUM_COLUMNS];
  private final int[] updatedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private final int[] predicateColumns = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final long[] matchedKeys = new long[SqlCommand.MAXIMUM_INSERT_ROWS];
  private final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private final long[] projectedValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final ValueIndexLookupResult joinOuterIndexed = new ValueIndexLookupResult();
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
  private int predicateCount;
  private int accessPredicate;
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
    if (status.isOk() && command.type() == SqlCommandType.COUNT) {
      status = execute(sql, aggregateExecution);
      if (status.isOk()) {
        status = cursor.claimAggregate(
            this,
            aggregateExecution.value(),
            aggregateExecution.transactionActive(),
            aggregateExecution.commitSequence());
      }
      if (status.isOk()) {
        scanActive = true;
      }
      return status;
    }
    if (status.isOk() && command.type() == SqlCommandType.GROUP_COUNT) {
      boolean implicit = !transactionActive;
      if (implicit) {
        status = session.begin(IsolationLevel.READ_COMMITTED);
      }
      if (status.isOk()) {
        status = session.resolveTable(command.tableName(), table);
      }
      if (status.isOk()) {
        status = bindPredicates(false);
      }
      if (status.isOk()
          && command.columnTableName(0).length() > 0
          && !sameName(command.columnTableName(0), command.tableName())) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int groupColumn = status.isOk()
          ? table.findColumn(command.firstColumnName()) : -1;
      boolean valueIndex = groupColumn > 0 && table.hasIndexOn(groupColumn);
      if (status.isOk()
          && (groupColumn < 0 || groupColumn > 0 && !valueIndex)) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (status.isOk()) {
        status = beginOrderedAggregateScan(cursor, groupColumn, valueIndex);
      }
      if (status.isOk()) {
        status = cursor.claimGroupCount(
            this, implicit, groupColumn, valueIndex, command.rowLimit());
      }
      if (status.isOk()) {
        scanActive = true;
      } else if (implicit) {
        session.abort(outcome);
      }
      return status;
    }
    if (status.isOk() && command.type() == SqlCommandType.DISTINCT_SCAN) {
      boolean implicit = !transactionActive;
      if (implicit) {
        status = session.begin(IsolationLevel.READ_COMMITTED);
      }
      if (status.isOk()) {
        status = session.resolveTable(command.tableName(), table);
      }
      if (status.isOk()) {
        status = bindPredicates(false);
      }
      if (status.isOk()
          && command.columnTableName(0).length() > 0
          && !sameName(command.columnTableName(0), command.tableName())) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int distinctColumn = status.isOk()
          ? table.findColumn(command.firstColumnName()) : -1;
      boolean valueIndex = distinctColumn > 0 && table.hasIndexOn(distinctColumn);
      if (status.isOk()
          && (distinctColumn < 0 || distinctColumn > 0 && !valueIndex)) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (status.isOk()) {
        status = beginOrderedAggregateScan(cursor, distinctColumn, valueIndex);
      }
      if (status.isOk()) {
        status = cursor.claimDistinct(
            this, implicit, distinctColumn, valueIndex, command.rowLimit());
      }
      if (status.isOk()) {
        scanActive = true;
      } else if (implicit) {
        session.abort(outcome);
      }
      return status;
    }
    if (status.isOk() && command.type() == SqlCommandType.JOIN_SCAN) {
      boolean implicit = !transactionActive;
      if (implicit) {
        status = session.begin(IsolationLevel.READ_COMMITTED);
      }
      if (status.isOk()) {
        status = session.resolveTable(command.tableName(), table);
      }
      if (status.isOk()) {
        status = session.resolveTable(command.joinTableName(), joinTable);
      }
      if (status.isOk()) {
        status = bindJoin();
      }
      boolean predicate = status.isOk() && predicateCount > 0;
      boolean equality = predicate && accessEquality();
      boolean indexedOuter = predicate
          && predicateColumn > 0
          && table.hasIndexOn(predicateColumn);
      boolean primaryRange = predicate && predicateColumn == 0;
      if (status.isOk()
          && predicate
          && equality
          && (indexedOuter || primaryRange)
          && accessValue() == Long.MAX_VALUE) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      long lower = equality ? accessValue() : accessLowerInclusive();
      long upper = equality ? accessValue() + 1 : accessUpperExclusive();
      if (status.isOk()) {
        status = indexedOuter
            ? session.beginValueScan(
                table, predicateColumn, lower, upper, cursor.relational())
            : primaryRange
                ? session.beginScan(table, lower, upper, cursor.relational())
                : session.beginScan(table, cursor.relational());
      }
      if (status.isOk()) {
        status = cursor.claimJoin(
            this,
            implicit,
            table.findColumn(command.joinOuterColumnName()),
            joinTable.findColumn(command.joinInnerColumnName()),
            indexedOuter,
            projectedColumns,
            projectedColumnCount,
            command.rowLimit());
      }
      if (status.isOk()) {
        scanActive = true;
      } else if (implicit) {
        session.abort(outcome);
      }
      return status;
    }
    if (!status.isOk()
        || command.type() != SqlCommandType.SCAN
            && command.type() != SqlCommandType.SELECT) {
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
    int orderColumn = status.isOk() && command.isOrdered()
        ? table.findColumn(command.orderColumnName()) : -1;
    if (status.isOk()
        && command.isOrdered()
        && (orderColumn < 0 || orderColumn > 0 && !table.hasIndexOn(orderColumn))) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean bounded = status.isOk() && predicateCount > 0;
    boolean equality = bounded && accessEquality();
    int scanIndexColumn = status.isOk() && command.isOrdered()
        ? orderColumn > 0 ? orderColumn : -1
        : status.isOk() && predicateColumn > 0 && table.hasIndexOn(predicateColumn)
            ? predicateColumn : -1;
    boolean valueIndex = scanIndexColumn > 0;
    boolean primaryRangeAccess = scanIndexColumn < 0 && predicateColumn == 0;
    if (status.isOk()
        && equality
        && predicateColumn == 0
        && scanIndexColumn < 0
        && accessValue() == Long.MAX_VALUE) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      if (valueIndex) {
        boolean boundedByScanIndex = bounded && predicateColumn == scanIndexColumn;
        if (boundedByScanIndex) {
          long lower = equality ? accessValue() : accessLowerInclusive();
          long upper = equality ? accessValue() + 1 : accessUpperExclusive();
          status = accessValue() == Long.MAX_VALUE && equality
              ? StatusCode.INVALID_EXTERNAL_INPUT
              : session.beginValueScan(
                  table, scanIndexColumn, lower, upper, cursor.relational());
        } else {
          status = session.beginValueScan(table, scanIndexColumn, cursor.relational());
        }
      } else {
        status = bounded && predicateColumn == 0
            ? session.beginScan(
                table,
                equality ? accessValue() : accessLowerInclusive(),
                equality ? accessValue() + 1 : accessUpperExclusive(),
                cursor.relational())
            : session.beginScan(table, cursor.relational());
      }
    }
    if (status.isOk()) {
      status = cursor.claim(
          this,
          implicit,
          valueIndex,
          projectedColumns,
          projectedColumnCount,
          command.rowLimit());
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
    if (!cursor.aggregate() && cursor.limitReached()) {
      return StatusCode.CONFLICT;
    }
    if (cursor.aggregate()) {
      if (cursor.rowsReturned() > 0) {
        return StatusCode.CONFLICT;
      }
      projectedValues[0] = cursor.aggregateValue();
      result.set(0, projectedValues, 1);
      cursor.rowReturned();
      return StatusCode.OK;
    }
    if (cursor.groupCount()) {
      return nextGroupCount(cursor, result);
    }
    if (cursor.distinct()) {
      return nextDistinct(cursor, result);
    }
    if (cursor.join()) {
      return nextJoin(cursor, result);
    }
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
      status = validateRow(source);
      if (status.isOk() && !matchesPredicates(primaryKey, source)) {
        continue;
      }
      if (status.isOk()) {
        projectScanRow(primaryKey, source, cursor, projectedValues);
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
    if (cursor.aggregate()) {
      return index == 0 ? COUNT_COLUMN_NAME : null;
    }
    if (cursor.groupCount()) {
      return index == 0
          ? table.columnName(cursor.groupColumn())
          : index == 1 ? COUNT_COLUMN_NAME : null;
    }
    if (cursor.distinct()) {
      return index == 0 ? table.columnName(cursor.groupColumn()) : null;
    }
    if (cursor.join()) {
      int projection = cursor.projectedColumn(index);
      return projection >= 0
          ? table.columnName(projection)
          : joinTable.columnName(-projection - 1);
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
    if (cursor.aggregate()) {
      result.setTransaction(
          cursor.aggregateTransactionActive(),
          cursor.aggregateCommitSequence());
      cursor.complete();
      scanActive = false;
      return StatusCode.OK;
    }
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
      if (predicateCount != 1
          || !accessEquality()
          || predicateColumn > 0 && !table.hasUniqueIndexOn(predicateColumn)) {
        StatusCode status = collectMatchedKeys();
        for (int index = 0; status.isOk() && index < matchedRowCount; index++) {
          status = updatePrimaryKey(matchedKeys[index]);
        }
        return status;
      }
      long primaryKey = accessValue();
      StatusCode status = StatusCode.OK;
      if (predicateColumn > 0) {
        status = session.fetchByUniqueValue(
            table, predicateColumn, accessValue(), indexed);
        primaryKey = indexed.key();
      }
      if (status.isOk()) {
        status = updatePrimaryKey(primaryKey);
        matchedRowCount = status.isOk() ? 1 : 0;
      }
      return status;
    }
    if (command.type() == SqlCommandType.DELETE) {
      if (predicateCount != 1
          || !accessEquality()
          || predicateColumn > 0 && !table.hasUniqueIndexOn(predicateColumn)) {
        StatusCode status = collectMatchedKeys();
        for (int index = 0; status.isOk() && index < matchedRowCount; index++) {
          status = session.deleteLong(table, matchedKeys[index]);
        }
        return status;
      }
      long primaryKey = accessValue();
      StatusCode status = StatusCode.OK;
      if (predicateColumn > 0) {
        status = session.fetchByUniqueValue(
            table, predicateColumn, accessValue(), indexed);
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
      boolean predicate = predicateCount > 0;
      boolean equality = predicate && accessEquality();
      boolean indexed = predicate
          && predicateColumn > 0
          && table.hasIndexOn(predicateColumn);
      boolean boundedPrimaryKey = predicate && predicateColumn == 0;
      if ((indexed || boundedPrimaryKey)
          && equality
          && accessValue() == Long.MAX_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      long lower = equality ? accessValue() : accessLowerInclusive();
      long upper = equality ? accessValue() + 1 : accessUpperExclusive();
      StatusCode status = indexed
          ? session.beginValueScan(
              table, predicateColumn, lower, upper, aggregateCursor)
          : boundedPrimaryKey
              ? session.beginScan(table, lower, upper, aggregateCursor)
              : session.beginScan(table, aggregateCursor);
      boolean aggregateActive = status.isOk();
      while (status.isOk()) {
        HeapRowResult source;
        long primaryKey;
        if (indexed) {
          status = session.nextValueScan(
              table, aggregateCursor, aggregateRow, this.indexed);
          source = this.indexed.row();
          primaryKey = this.indexed.key();
        } else {
          status = session.nextScan(aggregateCursor, aggregateRow);
          source = aggregateRow.row();
          primaryKey = aggregateRow.key();
        }
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.OK;
          break;
        }
        if (status.isOk() && predicate) {
          status = validateRow(source);
        }
        if (status.isOk() && predicate && !matchesPredicates(primaryKey, source)) {
          continue;
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
    if (!accessEquality()
        || predicateColumn > 0 && !table.hasUniqueIndexOn(predicateColumn)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status;
    long primaryKey;
    HeapRowResult source;
    if (predicateColumn == 0) {
      primaryKey = accessValue();
      status = session.fetch(table, primaryKey, fetched);
      source = fetched;
    } else {
      status = session.fetchByUniqueValue(
          table, predicateColumn, accessValue(), indexed);
      primaryKey = indexed.key();
      source = indexed.row();
    }
    if (status.isOk()) {
      status = validateRow(source);
    }
    if (status.isOk() && !matchesPredicates(primaryKey, source)) {
      status = StatusCode.CONFLICT;
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
    predicateCount = 0;
    accessPredicate = -1;
    projectedColumnCount = 0;
    if (command.type() == SqlCommandType.COUNT) {
      return bindPredicates(false);
    }
    if (command.type() == SqlCommandType.INSERT) {
      return bindInsertColumns();
    }
    if (command.type() == SqlCommandType.SELECT) {
      StatusCode status = bindProjections();
      if (status.isOk()) {
        status = bindPredicates(false);
      }
      return status;
    }
    if (command.type() == SqlCommandType.SCAN) {
      StatusCode status = bindProjections();
      if (!status.isOk()) {
        return status;
      }
      return bindPredicates(false);
    }
    if (command.type() == SqlCommandType.UPDATE) {
      StatusCode status = bindPredicates(false);
      if (command.updateColumnCount() <= 0
          || command.updateColumnCount() != command.columnCount()
          || !status.isOk()) {
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
      return bindPredicates(false);
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode bindProjections() {
    int count = command.isSelectAll() ? table.columnCount() : command.columnCount();
    if (count <= 0 || count > projectedColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < count; index++) {
      if (!command.isSelectAll()
          && command.columnTableName(index).length() > 0
          && !sameName(command.columnTableName(index), command.tableName())) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
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

  private StatusCode bindJoin() {
    if (sameName(command.tableName(), command.joinTableName())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int outerJoinColumn = table.findColumn(command.joinOuterColumnName());
    int innerJoinColumn = joinTable.findColumn(command.joinInnerColumnName());
    if (outerJoinColumn < 0
        || innerJoinColumn < 0
        || innerJoinColumn > 0 && !joinTable.hasUniqueIndexOn(innerJoinColumn)
        || command.columnCount() <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < command.columnCount(); index++) {
      int descriptor;
      if (sameName(command.columnTableName(index), command.tableName())) {
        int column = table.findColumn(command.columnName(index));
        if (column < 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        descriptor = column;
      } else if (sameName(command.columnTableName(index), command.joinTableName())) {
        int column = joinTable.findColumn(command.columnName(index));
        if (column < 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        descriptor = -column - 1;
      } else {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      projectedColumns[index] = descriptor;
    }
    projectedColumnCount = command.columnCount();
    return bindPredicates(true);
  }

  private StatusCode bindPredicates(boolean qualified) {
    predicateCount = command.predicateCount();
    accessPredicate = -1;
    predicateColumn = -1;
    int accessScore = -1;
    for (int index = 0; index < predicateCount; index++) {
      if (qualified
          && !sameName(command.predicateTableName(index), command.tableName())) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int column = table.findColumn(command.predicateColumnName(index));
      if (column < 0
          || !command.isEqualityPredicate(index)
              && command.predicateUpperExclusive(index)
                  <= command.predicateLowerInclusive(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      predicateColumns[index] = column;
      boolean indexed = column == 0 || table.hasIndexOn(column);
      int score = !indexed ? 0
          : command.isEqualityPredicate(index)
              ? column == 0 || table.hasUniqueIndexOn(column) ? 3 : 2
              : 1;
      if (score > accessScore) {
        accessScore = score;
        accessPredicate = index;
        predicateColumn = column;
      }
    }
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
    StatusCode status = validateRow(source);
    if (!status.isOk()) {
      return status;
    }
    row.clear();
    row.limit(table.rowBytes());
    status = source.copyTo(row);
    if (status.isOk()) {
      row.position(0);
    }
    return status;
  }

  private StatusCode validateRow(HeapRowResult source) {
    return validateRow(source, table);
  }

  private static StatusCode validateRow(
      HeapRowResult source,
      TableDefinition definition) {
    if (source.length() != definition.rowBytes()) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private StatusCode nextGroupCount(SqlScanCursor cursor, SqlScanRowResult result) {
    if (cursor.groupInputExhausted() && !cursor.hasGroupLookahead()) {
      return StatusCode.CONFLICT;
    }
    long groupValue;
    long count = 1;
    if (cursor.hasGroupLookahead()) {
      groupValue = cursor.takeGroupLookahead();
    } else {
      StatusCode first = nextGroupValue(cursor);
      if (first == StatusCode.CONFLICT) {
        cursor.exhaustGroupInput();
        return StatusCode.CONFLICT;
      }
      if (!first.isOk()) {
        return first;
      }
      groupValue = projectedValues[0];
    }
    while (true) {
      StatusCode status = nextGroupValue(cursor);
      if (status == StatusCode.CONFLICT) {
        cursor.exhaustGroupInput();
        break;
      }
      if (!status.isOk()) {
        return status;
      }
      long value = projectedValues[0];
      if (value != groupValue) {
        cursor.setGroupLookahead(value);
        break;
      }
      if (count == Long.MAX_VALUE) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      count++;
    }
    projectedValues[0] = groupValue;
    projectedValues[1] = count;
    result.set(groupValue, projectedValues, 2);
    cursor.rowReturned();
    return StatusCode.OK;
  }

  private StatusCode nextDistinct(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status = nextGroupValue(cursor);
      if (!status.isOk()) {
        return status;
      }
      long value = projectedValues[0];
      if (cursor.hasDistinctValue() && cursor.distinctValue() == value) {
        continue;
      }
      cursor.setDistinctValue(value);
      result.set(value, projectedValues, 1);
      cursor.rowReturned();
      return StatusCode.OK;
    }
  }

  private StatusCode nextJoin(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status;
      long outerKey;
      HeapRowResult outerRow;
      if (cursor.valueIndex()) {
        status = session.nextValueScan(
            table, cursor.relational(), aggregateRow, joinOuterIndexed);
        outerKey = joinOuterIndexed.key();
        outerRow = joinOuterIndexed.row();
      } else {
        status = session.nextScan(cursor.relational(), aggregateRow);
        outerKey = aggregateRow.key();
        outerRow = aggregateRow.row();
      }
      if (!status.isOk()) {
        return status;
      }
      status = validateRow(outerRow, table);
      if (!status.isOk()) {
        return status;
      }
      if (!matchesPredicates(outerKey, outerRow)) {
        continue;
      }
      long joinValue = readColumn(outerKey, outerRow, cursor.joinOuterColumn());
      long innerKey = joinValue;
      HeapRowResult innerRow = fetched;
      if (cursor.joinInnerColumn() == 0) {
        status = session.fetch(joinTable, joinValue, fetched);
      } else {
        status = session.fetchByUniqueValue(
            joinTable, cursor.joinInnerColumn(), joinValue, indexed);
        innerKey = indexed.key();
        innerRow = indexed.row();
      }
      if (status == StatusCode.CONFLICT
          || status == StatusCode.INVALID_EXTERNAL_INPUT) {
        continue;
      }
      if (!status.isOk()) {
        return status;
      }
      status = validateRow(innerRow, joinTable);
      if (!status.isOk()) {
        return status;
      }
      for (int index = 0; index < cursor.projectedColumnCount(); index++) {
        int projection = cursor.projectedColumn(index);
        projectedValues[index] = projection >= 0
            ? readColumn(outerKey, outerRow, projection)
            : readColumn(innerKey, innerRow, -projection - 1);
      }
      result.set(outerKey, projectedValues, cursor.projectedColumnCount());
      cursor.rowReturned();
      return StatusCode.OK;
    }
  }

  private StatusCode nextGroupValue(SqlScanCursor cursor) {
    while (true) {
      StatusCode status;
      long primaryKey;
      HeapRowResult source;
      if (cursor.valueIndex()) {
        status = session.nextValueScan(
            table, cursor.relational(), aggregateRow, indexed);
        primaryKey = indexed.key();
        source = indexed.row();
      } else {
        status = session.nextScan(cursor.relational(), aggregateRow);
        primaryKey = aggregateRow.key();
        source = aggregateRow.row();
      }
      if (status.isOk()) {
        status = validateRow(source, table);
      }
      if (!status.isOk()) {
        return status;
      }
      if (!matchesPredicates(primaryKey, source)) {
        continue;
      }
      int column = cursor.groupColumn();
      projectedValues[0] = column == 0
          ? primaryKey : source.getLong((column - 1) * Long.BYTES);
      return StatusCode.OK;
    }
  }

  private StatusCode beginOrderedAggregateScan(
      SqlScanCursor cursor,
      int orderedColumn,
      boolean valueIndex) {
    int boundedPredicate = -1;
    for (int index = 0; index < predicateCount; index++) {
      if (predicateColumns[index] == orderedColumn
          && (boundedPredicate < 0 || command.isEqualityPredicate(index))) {
        boundedPredicate = index;
        if (command.isEqualityPredicate(index)) {
          break;
        }
      }
    }
    if (boundedPredicate < 0) {
      return valueIndex
          ? session.beginValueScan(table, orderedColumn, cursor.relational())
          : session.beginScan(table, cursor.relational());
    }
    boolean equality = command.isEqualityPredicate(boundedPredicate);
    long lower = equality
        ? command.predicateValue(boundedPredicate)
        : command.predicateLowerInclusive(boundedPredicate);
    if (equality && lower == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long upper = equality
        ? lower + 1
        : command.predicateUpperExclusive(boundedPredicate);
    return valueIndex
        ? session.beginValueScan(
            table, orderedColumn, lower, upper, cursor.relational())
        : session.beginScan(table, lower, upper, cursor.relational());
  }

  private static long readColumn(long primaryKey, HeapRowResult source, int column) {
    return column == 0
        ? primaryKey : source.getLong((column - 1) * Long.BYTES);
  }

  private boolean matchesPredicates(long primaryKey, HeapRowResult source) {
    for (int index = 0; index < predicateCount; index++) {
      long value = readColumn(primaryKey, source, predicateColumns[index]);
      boolean matches = command.isEqualityPredicate(index)
          ? value == command.predicateValue(index)
          : value >= command.predicateLowerInclusive(index)
              && value < command.predicateUpperExclusive(index);
      if (!matches) {
        return false;
      }
    }
    return true;
  }

  private boolean accessEquality() {
    return accessPredicate >= 0 && command.isEqualityPredicate(accessPredicate);
  }

  private long accessValue() {
    return command.predicateValue(accessPredicate);
  }

  private long accessLowerInclusive() {
    return command.predicateLowerInclusive(accessPredicate);
  }

  private long accessUpperExclusive() {
    return command.predicateUpperExclusive(accessPredicate);
  }

  private static boolean sameName(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  private StatusCode projectRow(
      long primaryKey,
      HeapRowResult source,
      int[] columns,
      int columnCount,
      long[] destination) {
    StatusCode status = validateRow(source);
    if (status.isOk()) {
      for (int index = 0; index < columnCount; index++) {
        int column = columns[index];
        destination[index] = readColumn(primaryKey, source, column);
      }
    }
    return status;
  }

  private void projectScanRow(
      long primaryKey,
      HeapRowResult source,
      SqlScanCursor cursor,
      long[] destination) {
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int column = cursor.projectedColumn(index);
      destination[index] = readColumn(primaryKey, source, column);
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
    boolean equality = accessEquality();
    boolean indexed = predicateColumn > 0 && table.hasIndexOn(predicateColumn);
    boolean primaryRange = predicateColumn == 0;
    if ((indexed || primaryRange)
        && equality
        && accessValue() == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long lower = equality ? accessValue() : accessLowerInclusive();
    long upper = equality ? accessValue() + 1 : accessUpperExclusive();
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
      HeapRowResult source = indexed ? this.indexed.row() : aggregateRow.row();
      long primaryKey = indexed ? this.indexed.key() : aggregateRow.key();
      if (status.isOk()) {
        status = validateRow(source);
      }
      if (status.isOk() && !matchesPredicates(primaryKey, source)) {
        continue;
      }
      if (status.isOk() && matchedRowCount >= matchedKeys.length) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      if (status.isOk()) {
        matchedKeys[matchedRowCount++] = primaryKey;
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
