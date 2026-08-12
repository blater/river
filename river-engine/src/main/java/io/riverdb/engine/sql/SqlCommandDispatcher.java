package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.SequenceValueResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.tx.api.IsolationLevel;
import java.nio.ByteBuffer;

/** Executes control commands and catalog mutations that do not open row scans. */
final class SqlCommandDispatcher {
  private final RelationalDatabase database;
  private final RelationalSession session;
  private final SqlTransactionState transactions;
  private final CheckpointResult checkpoint = new CheckpointResult();
  private final SequenceValueResult sequenceValue = new SequenceValueResult();
  private final SqlExpressionEvaluator expressions = new SqlExpressionEvaluator();
  private final TableDefinition createdTable = new TableDefinition();
  private final TableDefinition referencedTable = new TableDefinition();
  private final TableSchema createSchema = new TableSchema();
  private final ByteBuffer row = ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);

  SqlCommandDispatcher(
      RelationalDatabase relational,
      RelationalSession relationalSession,
      SqlTransactionState transactionState) {
    database = relational;
    session = relationalSession;
    transactions = transactionState;
  }

  boolean handles(SqlCommandType type) {
    return type == SqlCommandType.BEGIN
        || type == SqlCommandType.SAVEPOINT
        || type == SqlCommandType.ROLLBACK_TO_SAVEPOINT
        || type == SqlCommandType.RELEASE_SAVEPOINT
        || type == SqlCommandType.COMMIT
        || type == SqlCommandType.ROLLBACK
        || type == SqlCommandType.CREATE_VIEW
        || type == SqlCommandType.DROP_VIEW
        || type == SqlCommandType.CREATE_TABLE
        || type == SqlCommandType.CREATE_SEQUENCE
        || type == SqlCommandType.CREATE_INDEX
        || type == SqlCommandType.CREATE_UNIQUE_INDEX
        || type == SqlCommandType.DROP_SEQUENCE
        || type == SqlCommandType.DROP_INDEX
        || type == SqlCommandType.DROP_TABLE
        || type == SqlCommandType.ALTER_TABLE_RENAME
        || type == SqlCommandType.ALTER_TABLE_RENAME_COLUMN
        || type == SqlCommandType.ALTER_INDEX_RENAME
        || type == SqlCommandType.NEXT_SEQUENCE_VALUE
        || type == SqlCommandType.CHECKPOINT;
  }

  StatusCode execute(
      SqlCommand command,
      SqlViewDefinitionValidator viewValidator,
      SqlAtomicStatementLifecycle atomic,
      SqlExecutionResult result) {
    SqlCommandType type = command.type();
    if (type == SqlCommandType.BEGIN) {
      IsolationLevel isolation = command.isReadCommittedTransaction()
          ? IsolationLevel.READ_COMMITTED
          : command.isSerializableTransaction()
              ? IsolationLevel.SERIALIZABLE : IsolationLevel.REPEATABLE_READ;
      StatusCode status = transactions.beginExplicit(isolation);
      if (status.isOk()) {
        result.setTransaction(true, 0);
      }
      return status;
    }
    if (type == SqlCommandType.SAVEPOINT) {
      return finishSavepoint(
          transactions.createUserSavepoint(command.savepointName()), result);
    }
    if (type == SqlCommandType.ROLLBACK_TO_SAVEPOINT) {
      return finishSavepoint(
          transactions.rollbackToUserSavepoint(command.savepointName()), result);
    }
    if (type == SqlCommandType.RELEASE_SAVEPOINT) {
      return finishSavepoint(
          transactions.releaseUserSavepoint(command.savepointName()), result);
    }
    if (type == SqlCommandType.COMMIT) {
      StatusCode status = transactions.commitExplicit();
      if (status.isOk()) {
        result.setTransaction(false, transactions.commitSequence());
      }
      return status;
    }
    if (type == SqlCommandType.ROLLBACK) {
      StatusCode status = transactions.abortExplicit();
      if (status.isOk()) {
        result.setTransaction(false, 0);
      }
      return status;
    }
    if (type == SqlCommandType.NEXT_SEQUENCE_VALUE) {
      StatusCode status = database.nextSequenceValue(
          command.sequenceName(), sequenceValue);
      if (status.isOk()) {
        result.setScalar(sequenceValue.value(), sequenceValue.commitSequence());
        if (transactions.isExplicit()) {
          result.setTransaction(true, session.visibleCommitSequence());
        }
      }
      return status;
    }
    if (type == SqlCommandType.CHECKPOINT) {
      if (transactions.isExplicit()) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = database.checkpoint(checkpoint);
      if (status.isOk()) {
        result.setUpdate(0, checkpoint.commitSequence());
      }
      return status;
    }
    if (type == SqlCommandType.CREATE_VIEW || type == SqlCommandType.DROP_VIEW) {
      return executeViewChange(command, viewValidator, atomic, result);
    }
    if (type == SqlCommandType.CREATE_TABLE) {
      return executeCreateTable(command, atomic, result);
    }
    return executeCatalogMutation(command, atomic, result);
  }

  private StatusCode executeCreateTable(
      SqlCommand command,
      SqlAtomicStatementLifecycle atomic,
      SqlExecutionResult result) {
    StatusCode status = prepareCreateSchema(command);
    if (!status.isOk()) {
      return status;
    }
    if (!transactions.isExplicit()
        && !command.hasUniqueColumns()
        && !command.hasReferences()) {
      status = database.createTable(
          command.tableName(), createSchema, createdTable);
      if (status.isOk()) {
        result.setUpdate(0, 0);
      }
      return status;
    }
    status = atomic.begin(IsolationLevel.SERIALIZABLE);
    boolean began = status.isOk();
    boolean implicit = began && atomic.implicit();
    if (status.isOk()) {
      status = resolveCreateReferences(command);
    }
    if (status.isOk()) {
      status = session.createTable(
          command.tableName(), createSchema, createdTable);
    }
    if (status.isOk()) {
      status = createConstraintIndexes(command);
    }
    if (began) {
      status = atomic.finish(status);
    }
    if (status.isOk()) {
      long commitSequence = implicit ? transactions.commitSequence() : 0;
      result.setUpdate(0, commitSequence);
      result.setTransaction(transactions.isExplicit(), commitSequence);
    }
    return status;
  }

  private StatusCode executeViewChange(
      SqlCommand command,
      SqlViewDefinitionValidator viewValidator,
      SqlAtomicStatementLifecycle atomic,
      SqlExecutionResult result) {
    boolean create = command.type() == SqlCommandType.CREATE_VIEW;
    CharSequence viewName = command.tableName();
    CharSequence viewSql = command.viewQuery();
    StatusCode status = atomic.begin(IsolationLevel.SERIALIZABLE);
    boolean began = status.isOk();
    boolean implicit = began && atomic.implicit();
    int tableId = 0;
    if (status.isOk() && create) {
      status = viewValidator.validate(session, viewSql);
      tableId = status.isOk() ? viewValidator.tableId() : 0;
    }
    if (status.isOk()) {
      status = create
          ? session.createView(viewName, viewSql, tableId)
          : session.dropView(viewName);
    }
    if (began) {
      status = atomic.finish(status);
    }
    if (status.isOk()) {
      long commitSequence = implicit ? transactions.commitSequence() : 0;
      result.setUpdate(0, commitSequence);
      result.setTransaction(transactions.isExplicit(), commitSequence);
    }
    return status;
  }

  private StatusCode prepareCreateSchema(SqlCommand command) {
    createSchema.reset();
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < command.columnCount(); index++) {
      status = createSchema.addColumn(
          command.columnName(index),
          command.columnTypeDescriptor(index),
          !command.columnIsNotNull(index));
      if (status.isOk() && command.columnHasDefault(index)) {
        if (command.columnIsVarchar(index)) {
          row.clear();
          int bytes = command.copyText(command.columnDefaultValue(index), row);
          if (bytes >= 0) {
            row.flip();
            status = createSchema.setLastTextDefault(row);
          } else {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          }
        } else {
          status = createSchema.setLastDefault(command.columnDefaultValue(index));
        }
      }
      if (status.isOk() && command.columnHasCheck(index)) {
        status = createSchema.setLastCheck(
            expressions.checkComparisonCode(command.columnCheckComparison(index)),
            command.columnCheckValue(index));
      }
    }
    if (status.isOk() && command.hasPrimaryKeyIdentity()) {
      status = createSchema.setPrimaryKeyIdentity();
    }
    return status;
  }

  private StatusCode createConstraintIndexes(SqlCommand command) {
    StatusCode status = StatusCode.OK;
    for (int column = 1;
        status.isOk() && column < command.columnCount();
        column++) {
      if (command.columnIsUnique(column) || command.columnHasReference(column)) {
        String kind = command.columnIsUnique(column) ? "unique" : "reference";
        String indexName = "_river_" + kind + "_" + createdTable.tableId()
            + "_" + column;
        status = session.createConstraintIndex(
            indexName,
            command.tableName(),
            command.columnName(column),
            command.columnIsUnique(column));
      }
    }
    return status;
  }

  private StatusCode resolveCreateReferences(SqlCommand command) {
    StatusCode status = StatusCode.OK;
    for (int column = 1;
        status.isOk() && column < command.columnCount();
        column++) {
      if (!command.columnHasReference(column)) {
        continue;
      }
      status = session.resolveTable(
          command.columnReferenceTableName(column), referencedTable);
      int referencedColumn = status.isOk()
          ? referencedTable.findColumn(command.columnReferenceColumnName(column)) : -1;
      if (status.isOk()
          && (referencedColumn != 0
              || referencedTable.typeDescriptor(referencedColumn)
                  != io.riverdb.base.type.SqlTypeDescriptor.BIGINT)) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (status.isOk()) {
        status = createSchema.setReference(column, referencedTable.tableId());
      }
    }
    return status;
  }

  private StatusCode finishSavepoint(
      StatusCode status, SqlExecutionResult result) {
    if (status.isOk()) {
      result.setTransaction(true, session.visibleCommitSequence());
    }
    return status;
  }

  private StatusCode executeCatalogMutation(
      SqlCommand command,
      SqlAtomicStatementLifecycle atomic,
      SqlExecutionResult result) {
    if (!transactions.isExplicit()) {
      StatusCode status = mutateDatabase(command);
      if (status.isOk()) {
        result.setUpdate(0, 0);
      }
      return status;
    }
    StatusCode status = atomic.begin(IsolationLevel.SERIALIZABLE);
    boolean began = status.isOk();
    if (status.isOk()) {
      status = mutateSession(command);
    }
    if (began) {
      status = atomic.finish(status);
    }
    if (status.isOk()) {
      result.setUpdate(0, 0);
      result.setTransaction(true, session.visibleCommitSequence());
    }
    return status;
  }

  private StatusCode mutateDatabase(SqlCommand command) {
    SqlCommandType type = command.type();
    if (type == SqlCommandType.CREATE_SEQUENCE) {
      return database.createSequence(
          command.sequenceName(),
          command.sequenceStart(),
          command.sequenceIncrement());
    }
    if (type == SqlCommandType.CREATE_INDEX
        || type == SqlCommandType.CREATE_UNIQUE_INDEX) {
      return type == SqlCommandType.CREATE_UNIQUE_INDEX
          ? database.createUniqueValueIndex(
              command.indexName(), command.tableName(), command.firstColumnName())
          : database.createValueIndex(
              command.indexName(), command.tableName(), command.firstColumnName());
    }
    if (type == SqlCommandType.DROP_SEQUENCE) {
      return database.dropSequence(command.sequenceName());
    }
    if (type == SqlCommandType.DROP_INDEX) {
      return database.dropValueIndex(command.indexName(), command.tableName());
    }
    if (type == SqlCommandType.DROP_TABLE) {
      return database.dropTable(command.tableName());
    }
    if (type == SqlCommandType.ALTER_TABLE_RENAME) {
      return database.renameTable(command.tableName(), command.renamedTableName());
    }
    if (type == SqlCommandType.ALTER_TABLE_RENAME_COLUMN) {
      return database.renameColumn(
          command.tableName(),
          command.firstColumnName(),
          command.secondColumnName());
    }
    if (type == SqlCommandType.ALTER_INDEX_RENAME) {
      return database.renameIndex(command.indexName(), command.renamedIndexName());
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode mutateSession(SqlCommand command) {
    SqlCommandType type = command.type();
    if (type == SqlCommandType.CREATE_SEQUENCE) {
      return session.createSequence(
          command.sequenceName(),
          command.sequenceStart(),
          command.sequenceIncrement());
    }
    if (type == SqlCommandType.CREATE_INDEX
        || type == SqlCommandType.CREATE_UNIQUE_INDEX) {
      return session.createValueIndex(
          command.indexName(),
          command.tableName(),
          command.firstColumnName(),
          type == SqlCommandType.CREATE_UNIQUE_INDEX);
    }
    if (type == SqlCommandType.DROP_SEQUENCE) {
      return session.dropSequence(command.sequenceName());
    }
    if (type == SqlCommandType.DROP_INDEX) {
      return session.dropValueIndex(command.indexName(), command.tableName());
    }
    if (type == SqlCommandType.DROP_TABLE) {
      return session.dropTable(command.tableName());
    }
    if (type == SqlCommandType.ALTER_TABLE_RENAME) {
      return session.renameTable(command.tableName(), command.renamedTableName());
    }
    if (type == SqlCommandType.ALTER_TABLE_RENAME_COLUMN) {
      return session.renameColumn(
          command.tableName(),
          command.firstColumnName(),
          command.secondColumnName());
    }
    if (type == SqlCommandType.ALTER_INDEX_RENAME) {
      return session.renameIndex(command.indexName(), command.renamedIndexName());
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
