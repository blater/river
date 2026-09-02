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
  private final SqlTemporalContext temporal;
  private final CheckpointResult checkpoint = new CheckpointResult();
  private final SequenceValueResult sequenceValue = new SequenceValueResult();
  private final SqlExpressionEvaluator expressions = new SqlExpressionEvaluator();
  private final SqlScalarExpressionEvaluator scalarExpressions =
      new SqlScalarExpressionEvaluator();
  private final SqlCheckExpressionBinder checks = new SqlCheckExpressionBinder();
  private final TableDefinition createdTable = new TableDefinition();
  private final TableDefinition referencedTable = new TableDefinition();
  private final TableSchema createSchema = new TableSchema();
  private final SqlDescriptorTableCreation descriptorTables =
      new SqlDescriptorTableCreation();
  private final SqlDescriptorIndexCreation descriptorIndexes =
      new SqlDescriptorIndexCreation();
  private final SqlDescriptorIndexDrop descriptorIndexDrops =
      new SqlDescriptorIndexDrop();
  private final SqlDescriptorTableDrop descriptorTableDrops =
      new SqlDescriptorTableDrop();
  private final SqlDescriptorTableRename descriptorTableRenames =
      new SqlDescriptorTableRename();
  private final SqlDescriptorColumnRename descriptorColumnRenames =
      new SqlDescriptorColumnRename();
  private final SqlDescriptorIndexRename descriptorIndexRenames =
      new SqlDescriptorIndexRename();
  private final ByteBuffer row = ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
  private SqlAnalyzeTableExecution analyzeTables;

  SqlCommandDispatcher(
      RelationalDatabase relational,
      RelationalSession relationalSession,
      SqlTransactionState transactionState,
      SqlTemporalContext temporalContext) {
    database = relational;
    session = relationalSession;
    transactions = transactionState;
    temporal = temporalContext;
  }

  boolean handles(SqlCommandType type) {
    return SqlCommandDispatchTypes.handles(type);
  }

  boolean hasResources() {
    return analyzeTables != null && analyzeTables.hasResources();
  }

  StatusCode closeResources() {
    return analyzeTables == null ? StatusCode.OK : analyzeTables.closeResources();
  }

  StatusCode execute(
      SqlCommand command,
      SqlViewDefinitionValidator viewValidator,
      SqlAtomicStatementLifecycle atomic,
      SqlExecutionResult result) {
    SqlCommandType type = command.type();
    if (type == SqlCommandType.SET_TIME_ZONE) {
      StatusCode status = temporal.setTimeZone(command);
      if (status.isOk()) {
        result.setUpdate(0, 0);
        result.setTransaction(transactions.isExplicit(), session.visibleCommitSequence());
      }
      return status;
    }
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
      return SqlSequenceCommandExecution.execute(
          database, session, transactions, command, sequenceValue, result);
    }
    if (type == SqlCommandType.SCALAR_EXPRESSION) {
      return scalarExpressions.evaluate(command.scalarExpression(), result);
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
    if (type == SqlCommandType.ANALYZE_TABLE) {
      return executeAnalyzeTable(command, atomic, result);
    }
    if (type == SqlCommandType.CREATE_VIEW || type == SqlCommandType.DROP_VIEW) {
      return executeViewChange(command, viewValidator, atomic, result);
    }
    if (type == SqlCommandType.CREATE_TABLE) {
      return executeCreateTable(command, atomic, result);
    }
    if (type == SqlCommandType.CREATE_INDEX
        || type == SqlCommandType.CREATE_UNIQUE_INDEX) {
      StatusCode status = descriptorIndexes.execute(
          session, transactions, atomic, command, result);
      return descriptorIndexes.legacyTable()
          ? executeCatalogMutation(command, atomic, result) : status;
    }
    if (type == SqlCommandType.DROP_INDEX) {
      StatusCode status = descriptorIndexDrops.execute(
          session, transactions, atomic, command, result);
      return descriptorIndexDrops.legacyTable()
          ? executeCatalogMutation(command, atomic, result) : status;
    }
    if (type == SqlCommandType.DROP_TABLE) {
      StatusCode status = descriptorTableDrops.execute(
          session, transactions, atomic, command, result);
      return descriptorTableDrops.legacyTable()
          ? executeCatalogMutation(command, atomic, result) : status;
    }
    if (type == SqlCommandType.ALTER_TABLE_RENAME) {
      StatusCode status = descriptorTableRenames.execute(
          session, transactions, atomic, command, result);
      return descriptorTableRenames.legacyTable()
          ? executeCatalogMutation(command, atomic, result) : status;
    }
    if (type == SqlCommandType.ALTER_TABLE_RENAME_COLUMN) {
      StatusCode status = descriptorColumnRenames.execute(
          session, transactions, atomic, command, result);
      return descriptorColumnRenames.legacyTable()
          ? executeCatalogMutation(command, atomic, result) : status;
    }
    if (type == SqlCommandType.ALTER_INDEX_RENAME) {
      StatusCode status = descriptorIndexRenames.execute(
          session, transactions, atomic, command, result);
      return descriptorIndexRenames.legacyTable()
          ? executeCatalogMutation(command, atomic, result) : status;
    }
    return executeCatalogMutation(command, atomic, result);
  }

  private StatusCode executeAnalyzeTable(
      SqlCommand command,
      SqlAtomicStatementLifecycle atomic,
      SqlExecutionResult result) {
    StatusCode status = atomic.begin(IsolationLevel.SERIALIZABLE);
    boolean began = status.isOk();
    boolean implicit = began && atomic.implicit();
    if (status.isOk()) {
      if (analyzeTables == null) analyzeTables = new SqlAnalyzeTableExecution(session);
      status = analyzeTables.analyze(command.tableName());
    }
    if (began) status = atomic.finish(status);
    if (status.isOk()) {
      long commitSequence = implicit ? transactions.commitSequence() : 0;
      result.setUpdate(analyzeTables.rowCount(), commitSequence);
      result.setTransaction(transactions.isExplicit(), commitSequence);
    }
    return status;
  }

  private StatusCode executeCreateTable(
      SqlCommand command,
      SqlAtomicStatementLifecycle atomic,
      SqlExecutionResult result) {
    StatusCode admission = SqlCreateTableLifecycleAdmission.validate(command);
    if (!admission.isOk()) return admission;
    if (!SqlCreateTableLifecycleAdmission.requiresLegacy(command)) {
      return descriptorTables.execute(
          session, transactions, atomic, command, result);
    }
    StatusCode status = prepareCreateSchema(command);
    if (!status.isOk()) return status;
    status = atomic.begin(IsolationLevel.SERIALIZABLE);
    boolean began = status.isOk();
    boolean implicit = began && atomic.implicit();
    if (status.isOk()) status = resolveCreateReferences(command);
    if (status.isOk()) status = session.createTable(
        command.tableName(), createSchema, createdTable);
    if (status.isOk()) status = createConstraintIndexes(command);
    if (began) status = atomic.finish(status);
    if (status.isOk()) {
      long commit = implicit ? transactions.commitSequence() : 0;
      result.setUpdate(0, commit);
      result.setTransaction(transactions.isExplicit(), commit);
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
    if (status.isOk() && create) {
      status = viewValidator.validate(session, viewSql);
    }
    if (status.isOk()) {
      status = create
          ? session.createView(
              viewName,
              viewSql,
              viewValidator.tableIds(),
              viewValidator.tableCount())
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
    return SqlCreateSchemaPreparation.prepare(command, createSchema, row, checks, expressions);
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
    StatusCode admitted = SqlCompositeIndexAdmission.validate(command);
    if (!admitted.isOk()) return admitted;
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
