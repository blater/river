package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.SequenceValueResult;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlIdentifier;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlQuery;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/** Executes the first SQL point-statement subset through real catalog and transactions. */
public final class SqlSession {
  private static final String COUNT_COLUMN_NAME = "count";
  private static final String SUM_COLUMN_NAME = "sum";
  private static final String MIN_COLUMN_NAME = "min";
  private static final String MAX_COLUMN_NAME = "max";
  private static final String NULL_COLUMN_NAME = "null";
  private static final int NULL_PROJECTION = Integer.MIN_VALUE;
  private static final int NESTED_SCALAR = 1;
  private static final int NESTED_EXISTENCE = 2;
  private static final int NESTED_MEMBERSHIP = 3;
  private static final int MAXIMUM_MEMBERSHIP_VALUES = 1_024;
  private static final int MAXIMUM_SORT_ROWS = 1_024;
  private static final int MAXIMUM_SORT_RUNS = 64;
  private static final int MAXIMUM_SORT_RECORD_BYTES =
      (TableSchema.MAXIMUM_COLUMNS + 3) * Long.BYTES + Integer.BYTES;

  private final RelationalDatabase database;
  private final RelationalSession session;
  private final SqlParser parser = new SqlParser();
  private final SqlCommand command = new SqlCommand();
  private final SqlQuery query = new SqlQuery();
  private final SqlExecutionResult aggregateExecution = new SqlExecutionResult();
  private final SequenceValueResult sequenceValue = new SequenceValueResult();
  private final TableDefinition table = new TableDefinition();
  private final TableDefinition joinTable = new TableDefinition();
  private final TableDefinition scalarTable = new TableDefinition();
  private final TableDefinition referencedTable = new TableDefinition();
  private final TableSchema createSchema = new TableSchema();
  private final TransactionOutcome outcome = new TransactionOutcome();
  private final CheckpointResult checkpoint = new CheckpointResult();
  private final IndexedSavepoint statementSavepoint = new IndexedSavepoint();
  private final IndexedSavepoint userSavepoint = new IndexedSavepoint();
  private final char[] userSavepointName = new char[SqlIdentifier.MAXIMUM_LENGTH];
  private final int[] insertSourceByColumn = new int[TableSchema.MAXIMUM_COLUMNS];
  private final int[] updatedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private final int[] updateSourceColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private final int[] predicateColumns = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] scalarPredicateColumns =
      new int[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] scalarPredicateValueColumns =
      new int[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] scalarPredicateValueOuter =
      new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final TableDefinition[] recursiveTables =
      new TableDefinition[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final RelationalScanCursor[] recursiveCursors =
      new RelationalScanCursor[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final RelationalScanResult[] recursiveRows =
      new RelationalScanResult[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final ByteBuffer[] recursiveRowBuffers =
      new ByteBuffer[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final HeapRowResult[] recursiveRowCopies =
      new HeapRowResult[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final long[] recursiveKeys =
      new long[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final int[] recursiveProjections =
      new int[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final int[] recursivePredicateColumns = new int[
      SqlQuery.MAXIMUM_QUERY_BLOCKS * SqlCommand.MAXIMUM_PREDICATES];
  private final int[] recursivePredicateValueColumns = new int[
      SqlQuery.MAXIMUM_QUERY_BLOCKS * SqlCommand.MAXIMUM_PREDICATES];
  private final int[] recursivePredicateValueScopes = new int[
      SqlQuery.MAXIMUM_QUERY_BLOCKS * SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] recursiveScalarNulls =
      new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final long[] recursiveScalarValues =
      new long[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final boolean[] recursiveExistenceResults =
      new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private long[] recursiveMembershipValues;
  private final int[] recursiveMembershipCounts =
      new int[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final boolean[] recursiveMembershipNulls =
      new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final long[] matchedKeys = new long[SqlCommand.MAXIMUM_INSERT_ROWS];
  private final long[] generatedInsertKeys =
      new long[SqlCommand.MAXIMUM_INSERT_ROWS];
  private final long[] membershipValues = new long[MAXIMUM_MEMBERSHIP_VALUES];
  private final long[] membershipScratchValues =
      new long[MAXIMUM_MEMBERSHIP_VALUES];
  private long[] membershipCandidates = membershipValues;
  private final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private final long[] projectedValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final long[] sortRunOffsets = new long[MAXIMUM_SORT_RUNS];
  private final long[] sortRunReadOffsets = new long[MAXIMUM_SORT_RUNS];
  private final int[] sortRunRowCounts = new int[MAXIMUM_SORT_RUNS];
  private final int[] sortRunRowsRemaining = new int[MAXIMUM_SORT_RUNS];
  private final long[] sortMergeKeys = new long[MAXIMUM_SORT_RUNS];
  private final long[] sortMergePrimaryKeys = new long[MAXIMUM_SORT_RUNS];
  private final long[] sortMergeNullMasks = new long[MAXIMUM_SORT_RUNS];
  private final boolean[] sortMergeKeyNulls = new boolean[MAXIMUM_SORT_RUNS];
  private final long[] sortMergeValues =
      new long[MAXIMUM_SORT_RUNS * TableSchema.MAXIMUM_COLUMNS];
  private final boolean[] sortRunActive = new boolean[MAXIMUM_SORT_RUNS];
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final ValueIndexLookupResult joinOuterIndexed = new ValueIndexLookupResult();
  private final RelationalScanCursor aggregateCursor = new RelationalScanCursor();
  private final RelationalScanResult aggregateRow = new RelationalScanResult();
  private final RelationalScanCursor scalarCursor = new RelationalScanCursor();
  private final RelationalScanResult scalarRow = new RelationalScanResult();
  private final HeapRowResult correlatedOuterRow = new HeapRowResult();
  private final ByteBuffer row = ByteBuffer.allocateDirect(
      TableSchema.MAXIMUM_COLUMNS * Long.BYTES);
  private final ByteBuffer correlatedOuterBuffer = ByteBuffer.allocateDirect(
      TableSchema.MAXIMUM_COLUMNS * Long.BYTES);
  private final ByteBuffer sortRecord = ByteBuffer.allocateDirect(
      MAXIMUM_SORT_RECORD_BYTES);
  private final CRC32C sortChecksum = new CRC32C();
  private long[] sortedKeys;
  private long[] sortedPrimaryKeys;
  private long[] sortedValues;
  private long[] sortedNullMasks;
  private boolean[] sortedKeyNulls;
  private FileChannel sortSpillChannel;
  private Path sortSpillPath;
  private boolean sortSpilled;
  private int sortRunCount;
  private int sortedTotalRows;
  private long sortSpillWriteOffset;
  private long sortedOutputPrimaryKey;
  private long sortedOutputNullMask;
  private boolean transactionActive;
  private boolean statementActive;
  private boolean userSavepointActive;
  private boolean scanActive;
  private boolean subqueryPredicateFalse;
  private boolean membershipHasNull;
  private boolean groupInputNull;
  private boolean groupAggregateInputNull;
  private boolean nestedCorrelated;
  private boolean correlatedScalar;
  private boolean correlatedExistence;
  private boolean correlatedMembership;
  private boolean correlatedNestedChain;
  private boolean recursiveNestedChain;
  private boolean recursiveRootCorrelated;
  private boolean existenceResult;
  private boolean scalarResultNull;
  private long scalarResultValue;
  private int membershipCandidateOffset;
  private boolean closed;
  private int userSavepointNameLength;
  private int predicateColumn;
  private int predicateCount;
  private int accessPredicate;
  private int updatedColumnCount;
  private int matchedRowCount;
  private int projectedColumnCount;
  private int sortedRowCount;
  private int nestedProjection;
  private int membershipValueCount;

  private SqlSession(RelationalDatabase relational, RelationalSession relationalSession) {
    database = relational;
    session = relationalSession;
  }

  private StatusCode beginStatement() {
    StatusCode status = session.beginStatement();
    if (status.isOk()) {
      statementActive = true;
    }
    return status;
  }

  private StatusCode completeStatement(StatusCode status) {
    if (!statementActive) {
      return status;
    }
    StatusCode completed = session.completeStatement();
    if (completed.isOk()) {
      statementActive = false;
    }
    return completed.isOk() ? status : completed;
  }

  private StatusCode failScanStart(
      StatusCode status,
      SqlScanCursor cursor,
      boolean implicit) {
    if (cursor.relational().isActive()) {
      StatusCode close = session.closeScan(cursor.relational());
      if (!close.isOk()) {
        status = close;
      }
    }
    status = completeStatement(status);
    if (implicit) {
      StatusCode abort = session.abort(outcome);
      if (!abort.isOk()) {
        status = abort;
      }
    }
    return status;
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
    query.reset();
    subqueryPredicateFalse = false;
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
      IsolationLevel isolation = command.isReadCommittedTransaction()
          ? IsolationLevel.READ_COMMITTED
          : command.isSerializableTransaction()
              ? IsolationLevel.SERIALIZABLE : IsolationLevel.REPEATABLE_READ;
      status = session.begin(isolation);
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
    if (command.type() == SqlCommandType.CREATE_SEQUENCE) {
      if (!transactionActive) {
        status = database.createSequence(
            command.sequenceName(),
            command.sequenceStart(),
            command.sequenceIncrement());
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.createSequence(
            command.sequenceName(),
            command.sequenceStart(),
            command.sequenceIncrement());
      }
      status = completeStatement(status);
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
    if (command.type() == SqlCommandType.CREATE_TABLE) {
      status = prepareCreateSchema();
      if (!status.isOk()) {
        return status;
      }
      if (!transactionActive
          && !command.hasUniqueColumns()
          && !command.hasReferences()) {
        status = database.createTable(
            command.tableName(), createSchema, table);
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      if (!transactionActive) {
        status = session.begin(IsolationLevel.SERIALIZABLE);
        boolean implicit = status.isOk();
        if (status.isOk()) {
          status = beginStatement();
        }
        if (status.isOk()) {
          status = resolveCreateReferences();
        }
        if (status.isOk()) {
          status = session.createTable(
              command.tableName(), createSchema, table);
        }
        if (status.isOk()) {
          status = createConstraintIndexes();
        }
        status = completeStatement(status);
        if (implicit) {
          StatusCode terminal = status.isOk()
              ? session.commit(outcome) : session.abort(outcome);
          if (!terminal.isOk()) {
            status = terminal;
          }
        }
        if (status.isOk()) {
          result.setUpdate(0, outcome.commitSequence());
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = resolveCreateReferences();
      }
      if (status.isOk()) {
        status = session.createTable(
            command.tableName(), createSchema, table);
      }
      if (status.isOk()) {
        status = createConstraintIndexes();
      }
      status = completeStatement(status);
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
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.createValueIndex(
            command.indexName(), command.tableName(), command.firstColumnName(), unique);
      }
      status = completeStatement(status);
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
    if (command.type() == SqlCommandType.DROP_SEQUENCE) {
      if (!transactionActive) {
        status = database.dropSequence(command.sequenceName());
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.dropSequence(command.sequenceName());
      }
      status = completeStatement(status);
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
    if (command.type() == SqlCommandType.DROP_INDEX) {
      if (!transactionActive) {
        status = database.dropValueIndex(command.indexName(), command.tableName());
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.dropValueIndex(
            command.indexName(), command.tableName());
      }
      status = completeStatement(status);
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
    if (command.type() == SqlCommandType.DROP_TABLE) {
      if (!transactionActive) {
        status = database.dropTable(command.tableName());
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.dropTable(command.tableName());
      }
      status = completeStatement(status);
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
    if (command.type() == SqlCommandType.ALTER_TABLE_RENAME) {
      if (!transactionActive) {
        status = database.renameTable(
            command.tableName(), command.renamedTableName());
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.renameTable(
            command.tableName(), command.renamedTableName());
      }
      status = completeStatement(status);
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
    if (command.type() == SqlCommandType.ALTER_TABLE_RENAME_COLUMN) {
      if (!transactionActive) {
        status = database.renameColumn(
            command.tableName(),
            command.firstColumnName(),
            command.secondColumnName());
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.renameColumn(
            command.tableName(),
            command.firstColumnName(),
            command.secondColumnName());
      }
      status = completeStatement(status);
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
    if (command.type() == SqlCommandType.ALTER_INDEX_RENAME) {
      if (!transactionActive) {
        status = database.renameIndex(
            command.indexName(), command.renamedIndexName());
        if (status.isOk()) {
          result.setUpdate(0, 0);
        }
        return status;
      }
      status = session.createSavepoint(statementSavepoint);
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.renameIndex(
            command.indexName(), command.renamedIndexName());
      }
      status = completeStatement(status);
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
    if (command.type() == SqlCommandType.NEXT_SEQUENCE_VALUE) {
      status = database.nextSequenceValue(command.sequenceName(), sequenceValue);
      if (status.isOk()) {
        result.setScalar(
            sequenceValue.value(), sequenceValue.commitSequence());
        if (transactionActive) {
          result.setTransaction(true, session.visibleCommitSequence());
        }
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
      status = beginStatement();
    }
    if (status.isOk()) {
      status = session.resolveTable(command.tableName(), table);
    }
    if (status.isOk()) {
      status = bindDataCommand();
    }
    if (status.isOk()
        && command.type() == SqlCommandType.INSERT
        && table.hasIdentity()) {
      status = allocateIdentityKeys();
    }
    if (status.isOk()) {
      status = executeDataCommand(result);
    }
    status = completeStatement(status);
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
    subqueryPredicateFalse = false;
    membershipValueCount = 0;
    membershipHasNull = false;
    membershipCandidates = membershipValues;
    membershipCandidateOffset = 0;
    nestedCorrelated = false;
    correlatedScalar = false;
    correlatedExistence = false;
    correlatedMembership = false;
    correlatedNestedChain = false;
    recursiveNestedChain = false;
    recursiveRootCorrelated = false;
    StatusCode status = parser.parseQuery(sql, query, command);
    if (status.isOk()
        && (command.type() == SqlCommandType.COUNT
            || command.type() == SqlCommandType.COUNT_VALUE
            || command.type() == SqlCommandType.NEXT_SEQUENCE_VALUE
            || isValueAggregate(command.type()))) {
      status = execute(sql, aggregateExecution);
      if (status.isOk()) {
        status = cursor.claimAggregate(
            this,
            aggregateExecution.value(),
            aggregateExecution.isNull(0),
            aggregateExecution.isVarchar(0),
            aggregateExecution.transactionActive(),
            aggregateExecution.commitSequence());
      }
      if (status.isOk()) {
        scanActive = true;
      }
      return status;
    }
    if (status.isOk() && isGroupAggregate(command.type())) {
      boolean implicit = !transactionActive;
      if (implicit) {
        status = session.begin(IsolationLevel.READ_COMMITTED);
      }
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.resolveTable(command.tableName(), table);
      }
      if (status.isOk()) {
        status = bindPredicates(false);
      }
      if (status.isOk()
          && command.columnTableName(0).length() > 0
          && !matchesTableQualifier(command, command.columnTableName(0))) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int groupColumn = status.isOk()
          ? table.findColumn(command.firstColumnName()) : -1;
      int aggregateColumn = -1;
      if (status.isOk() && command.type() != SqlCommandType.GROUP_COUNT) {
        if (command.columnCount() != 2
            || command.columnTableName(1).length() > 0
                && !matchesTableQualifier(command, command.columnTableName(1))) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
        } else {
          aggregateColumn = table.findColumn(command.columnName(1));
          if (aggregateColumn < 0) {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          } else if (command.type() == SqlCommandType.GROUP_SUM
              && table.isVarchar(aggregateColumn)) {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          }
        }
      }
      if (status.isOk() && groupColumn < 0) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      boolean orderedInput = groupColumn == 0
          || groupColumn > 0
              && table.hasIndexOn(groupColumn)
              && !table.isNullable(groupColumn);
      boolean inputValueIndex = orderedInput && groupColumn > 0;
      int sortedInputRows = -1;
      if (status.isOk() && orderedInput) {
        status = beginOrderedAggregateScan(
            cursor, groupColumn, inputValueIndex);
      } else if (status.isOk()) {
        boolean bounded = accessPredicate >= 0;
        boolean equality = bounded && accessEquality();
        int scanIndexColumn = bounded
                && predicateColumn > 0
                && table.hasIndexOn(predicateColumn)
            ? predicateColumn : -1;
        inputValueIndex = scanIndexColumn > 0;
        if (equality
            && (predicateColumn == 0 || inputValueIndex)
            && accessValue() == Long.MAX_VALUE) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
        } else if (inputValueIndex) {
          status = session.beginValueScan(
              table,
              scanIndexColumn,
              equality ? accessValue() : accessLowerInclusive(),
              equality ? accessValue() + 1 : accessUpperExclusive(),
              cursor.relational());
        } else if (bounded && predicateColumn == 0) {
          status = session.beginScan(
              table,
              equality ? accessValue() : accessLowerInclusive(),
              equality ? accessValue() + 1 : accessUpperExclusive(),
              cursor.relational());
        } else {
          status = session.beginScan(table, cursor.relational());
        }
        if (status.isOk()) {
          projectedColumns[0] = groupColumn;
          projectedColumns[1] = aggregateColumn < 0
              ? NULL_PROJECTION : aggregateColumn;
          projectedColumnCount = 2;
          status = materializeSortedScan(
              cursor, inputValueIndex, groupColumn);
          sortedInputRows = status.isOk() ? sortedTotalRows : -1;
        }
      }
      if (status.isOk()) {
        status = cursor.claimGroupAggregate(
            this,
            implicit,
            command.type(),
            groupColumn,
            aggregateColumn,
            inputValueIndex,
            sortedInputRows,
            command.rowLimit());
      }
      if (status.isOk()) {
        scanActive = true;
      } else {
        status = failScanStart(status, cursor, implicit);
      }
      return status;
    }
    if (status.isOk() && command.type() == SqlCommandType.DISTINCT_SCAN) {
      boolean implicit = !transactionActive;
      if (implicit) {
        status = session.begin(IsolationLevel.READ_COMMITTED);
      }
      if (status.isOk()) {
        status = beginStatement();
      }
      if (status.isOk()) {
        status = session.resolveTable(command.tableName(), table);
      }
      if (status.isOk()) {
        status = bindPredicates(false);
      }
      if (status.isOk()
          && command.columnTableName(0).length() > 0
          && !matchesTableQualifier(command, command.columnTableName(0))) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int distinctColumn = status.isOk()
          ? table.findColumn(command.firstColumnName()) : -1;
      if (status.isOk() && distinctColumn < 0) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      boolean orderedInput = distinctColumn == 0
          || distinctColumn > 0
              && table.hasIndexOn(distinctColumn)
              && !table.isNullable(distinctColumn);
      boolean inputValueIndex = orderedInput && distinctColumn > 0;
      int sortedInputRows = -1;
      if (status.isOk() && orderedInput) {
        status = beginOrderedAggregateScan(
            cursor, distinctColumn, inputValueIndex);
      } else if (status.isOk()) {
        boolean bounded = accessPredicate >= 0;
        boolean equality = bounded && accessEquality();
        int scanIndexColumn = bounded
                && predicateColumn > 0
                && table.hasIndexOn(predicateColumn)
            ? predicateColumn : -1;
        inputValueIndex = scanIndexColumn > 0;
        if (equality
            && (predicateColumn == 0 || inputValueIndex)
            && accessValue() == Long.MAX_VALUE) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
        } else if (inputValueIndex) {
          status = session.beginValueScan(
              table,
              scanIndexColumn,
              equality ? accessValue() : accessLowerInclusive(),
              equality ? accessValue() + 1 : accessUpperExclusive(),
              cursor.relational());
        } else if (bounded && predicateColumn == 0) {
          status = session.beginScan(
              table,
              equality ? accessValue() : accessLowerInclusive(),
              equality ? accessValue() + 1 : accessUpperExclusive(),
              cursor.relational());
        } else {
          status = session.beginScan(table, cursor.relational());
        }
        if (status.isOk()) {
          projectedColumns[0] = distinctColumn;
          projectedColumnCount = 1;
          status = materializeSortedScan(
              cursor, inputValueIndex, distinctColumn);
          sortedInputRows = status.isOk() ? sortedTotalRows : -1;
        }
      }
      if (status.isOk()) {
        status = cursor.claimDistinct(
            this,
            implicit,
            distinctColumn,
            inputValueIndex,
            sortedInputRows,
            command.rowLimit());
      }
      if (status.isOk()) {
        scanActive = true;
      } else {
        status = failScanStart(status, cursor, implicit);
      }
      return status;
    }
    if (status.isOk() && command.type() == SqlCommandType.JOIN_SCAN) {
      boolean implicit = !transactionActive;
      if (implicit) {
        status = session.begin(IsolationLevel.READ_COMMITTED);
      }
      if (status.isOk()) {
        status = beginStatement();
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
      boolean predicate = status.isOk() && accessPredicate >= 0;
      boolean equality = predicate && accessEquality();
      boolean indexedOuter = predicate
          && predicateColumn > 0
          && table.hasIndexOn(predicateColumn);
      boolean primaryRange = predicate && predicateColumn == 0;
      int outerJoinColumn = status.isOk()
          ? table.findColumn(command.joinOuterColumnName()) : -1;
      int innerJoinColumn = status.isOk()
          ? joinTable.findColumn(command.joinInnerColumnName()) : -1;
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
            outerJoinColumn,
            innerJoinColumn,
            indexedOuter,
            innerJoinColumn == 0 || joinTable.hasUniqueIndexOn(innerJoinColumn),
            projectedColumns,
            projectedColumnCount,
            command.rowLimit());
      }
      if (status.isOk()) {
        scanActive = true;
      } else {
        status = failScanStart(status, cursor, implicit);
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
      status = beginStatement();
    }
    if (status.isOk()) {
      status = session.resolveTable(command.tableName(), table);
    }
    if (status.isOk()
        && query.blockCount() > 2
        && (query.hasScalarPredicate()
            || query.hasExistencePredicate()
            || query.hasMembershipPredicate())) {
      status = prepareNestedChain();
    } else if (status.isOk() && query.hasScalarPredicate()) {
      status = evaluateScalarPredicate();
    } else if (status.isOk() && query.hasExistencePredicate()) {
      status = evaluateExistencePredicate();
    } else if (status.isOk() && query.hasMembershipPredicate()) {
      status = evaluateMembershipPredicate();
    }
    if (status.isOk()) {
      status = bindDataCommand();
    }
    int orderColumn = status.isOk() && command.isOrdered()
        ? resolveOrderColumn() : -1;
    if (status.isOk()
        && command.isOrdered()
        && orderColumn < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean materializedSort = status.isOk()
        && command.isOrdered()
        && (command.isDescendingOrder()
            || orderColumn > 0
                && (!table.hasIndexOn(orderColumn)
                    || table.isNullable(orderColumn)));
    boolean bounded = status.isOk() && accessPredicate >= 0;
    boolean equality = bounded && accessEquality();
    int scanIndexColumn = status.isOk() && command.isOrdered() && !materializedSort
        ? orderColumn > 0 ? orderColumn : -1
        : status.isOk() && predicateColumn > 0 && table.hasIndexOn(predicateColumn)
            ? predicateColumn : -1;
    boolean valueIndex = scanIndexColumn > 0;
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
      if (materializedSort) {
        status = materializeSortedScan(cursor, valueIndex, orderColumn);
        if (status.isOk()) {
          status = cursor.claimSorted(
              this,
              implicit,
              projectedColumns,
              projectedColumnCount,
              sortedTotalRows,
              command.rowLimit());
        }
      } else {
        status = cursor.claim(
            this,
            implicit,
            valueIndex,
            projectedColumns,
            projectedColumnCount,
            command.rowLimit());
      }
    }
    if (status.isOk()) {
      scanActive = true;
      return StatusCode.OK;
    }
    return failScanStart(status, cursor, implicit);
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
      result.set(
          0,
          projectedValues,
          cursor.aggregateNull() ? 1 : 0,
          cursor.aggregateVarchar() ? 1 : 0,
          1);
      cursor.rowReturned();
      return StatusCode.OK;
    }
    if (cursor.groupAggregate()) {
      return nextGroupAggregate(cursor, result);
    }
    if (cursor.distinct()) {
      return nextDistinct(cursor, result);
    }
    if (cursor.sorted()) {
      int sortedRow = cursor.currentSortedRow();
      if (sortedRow < 0) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = StatusCode.OK;
      long primaryKey;
      if (sortSpilled) {
        status = nextSpilledSortRow(cursor.projectedColumnCount());
        primaryKey = sortedOutputPrimaryKey;
      } else {
        int valueStart = sortedRow * TableSchema.MAXIMUM_COLUMNS;
        for (int index = 0; index < cursor.projectedColumnCount(); index++) {
          projectedValues[index] = sortedValues[valueStart + index];
        }
        primaryKey = sortedPrimaryKeys[sortedRow];
      }
      if (status.isOk()) {
        long nullMask = sortSpilled
            ? sortedOutputNullMask : sortedNullMasks[sortedRow];
        result.set(
            primaryKey,
            projectedValues,
            nullMask,
            scanProjectionVarcharMask(cursor),
            cursor.projectedColumnCount());
        cursor.advanceSortedRow();
        cursor.rowReturned();
      }
      return status;
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
      if (correlatedScalar || correlatedExistence) {
        subqueryPredicateFalse = false;
      }
      if (status.isOk() && recursiveNestedChain && recursiveRootCorrelated) {
        subqueryPredicateFalse = false;
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateRecursiveChain(primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
        if (status.isOk() && subqueryPredicateFalse) {
          continue;
        }
      }
      if (status.isOk() && correlatedNestedChain) {
        subqueryPredicateFalse = false;
        membershipValueCount = 0;
        membershipHasNull = false;
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateNestedChain(primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
        if (status.isOk() && subqueryPredicateFalse) {
          continue;
        }
      }
      if (status.isOk() && correlatedScalar) {
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateCorrelatedScalar(primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
        if (status.isOk() && subqueryPredicateFalse) {
          continue;
        }
      }
      if (status.isOk() && correlatedMembership) {
        membershipValueCount = 0;
        membershipHasNull = false;
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateCorrelatedMembership(
              primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
      }
      if (status.isOk() && !matchesPredicates(primaryKey, source)) {
        continue;
      }
      if (status.isOk() && correlatedExistence) {
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateCorrelatedExistence(
              primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
        if (status.isOk() && subqueryPredicateFalse) {
          continue;
        }
      }
      if (status.isOk()) {
        long nullMask = projectScanRow(
            primaryKey, source, cursor, projectedValues);
        result.set(
            primaryKey,
            projectedValues,
            nullMask,
            scanProjectionVarcharMask(cursor),
            cursor.projectedColumnCount());
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
      if (index != 0) {
        return null;
      }
      if (isValueAggregate(command.type())) {
        SqlIdentifier alias = command.columnAlias(0);
        if (alias != null && alias.length() > 0) {
          return alias;
        }
        return command.type() == SqlCommandType.SUM
            ? SUM_COLUMN_NAME
            : command.type() == SqlCommandType.MIN
                ? MIN_COLUMN_NAME : MAX_COLUMN_NAME;
      }
      if (command.type() == SqlCommandType.COUNT_VALUE) {
        SqlIdentifier alias = command.columnAlias(0);
        return alias != null && alias.length() > 0 ? alias : COUNT_COLUMN_NAME;
      }
      return COUNT_COLUMN_NAME;
    }
    if (cursor.groupAggregate()) {
      return index == 0
          ? command.columnOutputName(0)
          : index == 1 ? groupAggregateColumnName(cursor) : null;
    }
    if (cursor.distinct()) {
      return index == 0 ? command.columnOutputName(0) : null;
    }
    if (index >= 0 && index < command.columnCount()) {
      return command.columnOutputName(index);
    }
    int column = cursor.projectedColumn(index);
    return column == NULL_PROJECTION
        ? NULL_COLUMN_NAME : column < 0 ? null : table.columnName(column);
  }

  public boolean scanColumnIsVarchar(SqlScanCursor cursor, int index) {
    if (cursor == null
        || !cursor.isOwnedBy(this)
        || index < 0
        || index >= cursor.projectedColumnCount()) {
      return false;
    }
    if (cursor.aggregate()) {
      return index == 0 && cursor.aggregateVarchar();
    }
    if (cursor.groupAggregate()) {
      return (groupProjectionVarcharMask(cursor) & 1L << index) != 0;
    }
    if (cursor.distinct()) {
      return index == 0 && table.isVarchar(cursor.groupColumn());
    }
    int projection = cursor.projectedColumn(index);
    return projection >= 0
        ? table.isVarchar(projection)
        : cursor.join() && joinTable.isVarchar(-projection - 1);
  }

  private CharSequence groupAggregateColumnName(SqlScanCursor cursor) {
    if (cursor.groupAggregateType() != SqlCommandType.GROUP_COUNT) {
      SqlIdentifier alias = command.columnAlias(1);
      if (alias != null && alias.length() > 0) {
        return alias;
      }
    }
    return cursor.groupAggregateType() == SqlCommandType.GROUP_SUM
        ? SUM_COLUMN_NAME
        : cursor.groupAggregateType() == SqlCommandType.GROUP_MIN
            ? MIN_COLUMN_NAME
            : cursor.groupAggregateType() == SqlCommandType.GROUP_MAX
                ? MAX_COLUMN_NAME : COUNT_COLUMN_NAME;
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
    StatusCode status = StatusCode.OK;
    if (cursor.joinInnerScanActive()) {
      status = session.closeScan(cursor.joinInnerRelational());
      if (status.isOk()) {
        cursor.completeJoinInnerScan();
      }
    }
    if (status.isOk() && !cursor.sorted()) {
      status = session.closeScan(cursor.relational());
    }
    if (status.isOk() && cursor.sorted()) {
      status = closeSortSpill();
    }
    status = completeStatement(status);
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
        long key = table.hasIdentity()
            ? generatedInsertKeys[index]
            : command.insertValue(index, insertSourceByColumn[0]);
        status = table.checksSatisfied(key, row)
            ? session.insertRow(table, key, row)
            : StatusCode.CHECK_VIOLATION;
      }
      if (status.isOk() && table.hasIdentity()) {
        result.setGeneratedKey(generatedInsertKeys[0]);
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
    if (command.type() == SqlCommandType.COUNT
        || command.type() == SqlCommandType.COUNT_VALUE
        || isValueAggregate(command.type())) {
      boolean sum = command.type() == SqlCommandType.SUM;
      boolean minimum = command.type() == SqlCommandType.MIN;
      boolean valueAggregate = isValueAggregate(command.type());
      boolean countValue = command.type() == SqlCommandType.COUNT_VALUE;
      long aggregate = 0;
      long aggregateHigh = 0;
      boolean aggregatePresent = false;
      boolean filtered = predicateCount > 0;
      boolean bounded = accessPredicate >= 0;
      boolean equality = bounded && accessEquality();
      boolean indexed = bounded
          && predicateColumn > 0
          && table.hasIndexOn(predicateColumn);
      boolean boundedPrimaryKey = bounded && predicateColumn == 0;
      if ((indexed || boundedPrimaryKey)
          && equality
          && accessValue() == Long.MAX_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      long lower = bounded
          ? equality ? accessValue() : accessLowerInclusive() : 0;
      long upper = bounded
          ? equality ? accessValue() + 1 : accessUpperExclusive() : 0;
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
        if (status.isOk() && (filtered || valueAggregate || countValue)) {
          status = validateRow(source);
        }
        if (status.isOk() && filtered && !matchesPredicates(primaryKey, source)) {
          continue;
        }
        if (status.isOk()) {
          if (valueAggregate) {
            int column = projectedColumns[0];
            if (!isNull(source, table, column)) {
              long value = readColumn(primaryKey, source, column);
              if (sum) {
                long previous = aggregate;
                aggregate += value;
                aggregateHigh += (value < 0 ? -1 : 0)
                    + (Long.compareUnsigned(aggregate, previous) < 0 ? 1 : 0);
              } else if (!aggregatePresent
                  || minimum && value < aggregate
                  || !minimum && value > aggregate) {
                aggregate = value;
              }
              aggregatePresent = true;
            }
          } else {
            int column = countValue ? projectedColumns[0] : -1;
            if (!countValue || !isNull(source, table, column)) {
              if (aggregate == Long.MAX_VALUE) {
                status = StatusCode.RESOURCE_EXHAUSTED;
              } else {
                aggregate++;
                aggregatePresent = true;
              }
            }
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
      if (status.isOk()
          && sum
          && aggregatePresent
          && aggregateHigh != (aggregate < 0 ? -1 : 0)) {
        status = StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      }
      if (status.isOk()) {
        projectedValues[0] = aggregate;
        result.setProjection(
            0,
            projectedValues,
            valueAggregate && !aggregatePresent ? 1 : 0,
            aggregateProjectionVarcharMask(),
            1,
            0);
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
      result.setProjection(
          primaryKey,
          projectedValues,
          projectionNullMask(
              source, table, projectedColumns, projectedColumnCount),
          projectionVarcharMask(projectedColumns, projectedColumnCount),
          projectedColumnCount,
          0);
    }
    return status;
  }

  private boolean isSelect() {
    return command.type() == SqlCommandType.SELECT
        || command.type() == SqlCommandType.COUNT
        || command.type() == SqlCommandType.COUNT_VALUE
        || isValueAggregate(command.type());
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
    if (command.type() == SqlCommandType.COUNT_VALUE
        || isValueAggregate(command.type())) {
      StatusCode status = bindProjections();
      if (status.isOk()
          && command.type() == SqlCommandType.SUM
          && table.isVarchar(projectedColumns[0])) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return status.isOk() ? bindPredicates(false) : status;
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
        if (command.updateIsNull(index) && !table.isNullable(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (command.updateIsDefault(index) && !table.hasDefault(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (!command.updateIsNull(index)
            && !command.updateIsDefault(index)
            && !command.isRelativeUpdate(index)
            && command.updateIsVarchar(index) != table.isVarchar(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        updatedColumns[index] = column;
        if (command.isRelativeUpdate(index)) {
          int sourceColumn = table.findColumn(command.updateSourceColumnName(index));
          if (sourceColumn < 0
              || table.isVarchar(column)
              || table.isVarchar(sourceColumn)) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
          updateSourceColumns[index] = sourceColumn;
        } else {
          updateSourceColumns[index] = -1;
        }
      }
      updatedColumnCount = command.updateColumnCount();
      return StatusCode.OK;
    }
    if (command.type() == SqlCommandType.DELETE) {
      return bindPredicates(false);
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean isValueAggregate(SqlCommandType type) {
    return type == SqlCommandType.SUM
        || type == SqlCommandType.MIN
        || type == SqlCommandType.MAX;
  }

  private static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  private StatusCode bindProjections() {
    int count = command.isSelectAll() ? table.columnCount() : command.columnCount();
    if (count <= 0 || count > projectedColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < count; index++) {
      if (!command.isSelectAll()
          && command.columnTableName(index).length() > 0
          && !matchesTableQualifier(command, command.columnTableName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (!command.isSelectAll() && command.isNullProjection(index)) {
        if (command.isOrdered()) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        projectedColumns[index] = NULL_PROJECTION;
        continue;
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

  private long projectionVarcharMask(int[] projections, int count) {
    long mask = 0;
    for (int index = 0; index < count; index++) {
      int column = projections[index];
      if (column >= 0 && table.isVarchar(column)) {
        mask |= 1L << index;
      }
    }
    return mask;
  }

  private long scanProjectionVarcharMask(SqlScanCursor cursor) {
    long mask = 0;
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int projection = cursor.projectedColumn(index);
      boolean varchar = projection >= 0
          ? table.isVarchar(projection)
          : joinTable.isVarchar(-projection - 1);
      if (varchar) {
        mask |= 1L << index;
      }
    }
    return mask;
  }

  private long aggregateProjectionVarcharMask() {
    return (command.type() == SqlCommandType.MIN
            || command.type() == SqlCommandType.MAX)
        && projectedColumnCount > 0
        && table.isVarchar(projectedColumns[0])
        ? 1 : 0;
  }

  private long groupProjectionVarcharMask(SqlScanCursor cursor) {
    long mask = table.isVarchar(cursor.groupColumn()) ? 1 : 0;
    if ((cursor.groupAggregateType() == SqlCommandType.GROUP_MIN
            || cursor.groupAggregateType() == SqlCommandType.GROUP_MAX)
        && table.isVarchar(cursor.groupAggregateColumn())) {
      mask |= 1L << 1;
    }
    return mask;
  }

  private int resolveOrderColumn() {
    int column = table.findColumn(command.orderColumnName());
    if (column >= 0) {
      return column;
    }
    int resolved = -1;
    for (int index = 0; index < command.columnCount(); index++) {
      if (sameName(command.columnOutputName(index), command.orderColumnName())) {
        if (resolved >= 0 || command.isNullProjection(index)) {
          return -1;
        }
        resolved = table.findColumn(command.columnName(index));
      }
    }
    return resolved;
  }

  private StatusCode bindJoin() {
    if (matchesTableQualifier(command, command.joinTableName())
        || command.joinTableAlias().length() > 0
            && matchesTableQualifier(command, command.joinTableAlias())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int outerJoinColumn = table.findColumn(command.joinOuterColumnName());
    int innerJoinColumn = joinTable.findColumn(command.joinInnerColumnName());
    if (outerJoinColumn < 0
        || innerJoinColumn < 0
        || table.isVarchar(outerJoinColumn) != joinTable.isVarchar(innerJoinColumn)
        || innerJoinColumn > 0 && !joinTable.hasIndexOn(innerJoinColumn)
        || command.columnCount() <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < command.columnCount(); index++) {
      if (command.isNullProjection(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int descriptor;
      if (matchesTableQualifier(command, command.columnTableName(index))) {
        int column = table.findColumn(command.columnName(index));
        if (column < 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        descriptor = column;
      } else if (matchesJoinTableQualifier(command, command.columnTableName(index))) {
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
    return bindJoinPredicates();
  }

  private StatusCode bindJoinPredicates() {
    predicateCount = command.predicateCount();
    accessPredicate = -1;
    predicateColumn = -1;
    int accessScore = -1;
    for (int index = 0; index < predicateCount; index++) {
      boolean outer = matchesTableQualifier(
          command, command.predicateTableName(index));
      boolean inner = matchesJoinTableQualifier(
          command, command.predicateTableName(index));
      TableDefinition definition = outer ? table : inner ? joinTable : null;
      int column = definition == null
          ? -1 : definition.findColumn(command.predicateColumnName(index));
      if (column < 0
          || command.isColumnPredicate(index)
          || !command.isNullPredicate(index)
              && !(command.isLiteralMembership(index)
                  && command.literalMembershipCount(index) == 0)
              && definition.isVarchar(column) != command.predicateIsVarchar(index)
          || command.isRangePredicate(index)
              && command.predicateUpperExclusive(index)
                  <= command.predicateLowerInclusive(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      predicateColumns[index] = outer ? column : -column - 1;
      if (!outer || command.isNullPredicate(index)) {
        continue;
      }
      if (!command.isEqualityPredicate(index)
          && !command.isRangePredicate(index)) {
        continue;
      }
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

  private StatusCode bindPredicates(boolean qualified) {
    predicateCount = command.predicateCount();
    accessPredicate = -1;
    predicateColumn = -1;
    int accessScore = -1;
    for (int index = 0; index < predicateCount; index++) {
      if ((qualified || command.predicateTableName(index).length() > 0)
          && !matchesTableQualifier(command, command.predicateTableName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int column = table.findColumn(command.predicateColumnName(index));
      if (column < 0
          || command.isColumnPredicate(index)
          || !command.isNullPredicate(index)
              && !(command.isLiteralMembership(index)
                  && command.literalMembershipCount(index) == 0)
              && table.isVarchar(column) != command.predicateIsVarchar(index)
          || command.isRangePredicate(index)
              && command.predicateUpperExclusive(index)
                  <= command.predicateLowerInclusive(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      predicateColumns[index] = column;
      if (query.hasMembershipPredicate()
          && query.membershipPredicate() == index) {
        continue;
      }
      if ((correlatedScalar
              || correlatedNestedChain
              || recursiveNestedChain && recursiveRootCorrelated)
          && query.hasScalarPredicate()
          && query.scalarPredicate() == index) {
        continue;
      }
      if (command.isNullPredicate(index)) {
        continue;
      }
      if (!command.isEqualityPredicate(index)
          && !command.isRangePredicate(index)) {
        continue;
      }
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
      status = command.columnIsVarchar(index)
          ? createSchema.addVarchar7(
              command.columnName(index), !command.columnIsNotNull(index))
          : createSchema.addBigint(
              command.columnName(index), !command.columnIsNotNull(index));
      if (status.isOk() && command.columnHasDefault(index)) {
        status = createSchema.setLastDefault(command.columnDefaultValue(index));
      }
      if (status.isOk() && command.columnHasCheck(index)) {
        status = createSchema.setLastCheck(
            checkComparisonCode(command.columnCheckComparison(index)),
            command.columnCheckValue(index));
      }
    }
    if (status.isOk() && command.hasPrimaryKeyIdentity()) {
      status = createSchema.setPrimaryKeyIdentity();
    }
    return status;
  }

  private StatusCode createConstraintIndexes() {
    StatusCode status = StatusCode.OK;
    for (int column = 1;
        status.isOk() && column < command.columnCount();
        column++) {
      if (command.columnIsUnique(column) || command.columnHasReference(column)) {
        String kind = command.columnIsUnique(column) ? "unique" : "reference";
        String indexName = "_river_" + kind + "_" + table.tableId() + "_" + column;
        status = session.createConstraintIndex(
            indexName,
            command.tableName(),
            command.columnName(column),
            command.columnIsUnique(column));
      }
    }
    return status;
  }

  private StatusCode resolveCreateReferences() {
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
          && (referencedColumn != 0 || referencedTable.isVarchar(referencedColumn))) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (status.isOk()) {
        status = createSchema.setReference(column, referencedTable.tableId());
      }
    }
    return status;
  }

  private void encodeInsertRow(int rowIndex) {
    row.clear();
    row.limit(table.rowBytes());
    long nullMask = 0;
    for (int column = 1; column < table.columnCount(); column++) {
      int source = insertSourceByColumn[column];
      boolean omitted = source < 0;
      boolean explicitDefault = !omitted
          && command.insertIsDefault(rowIndex, source);
      boolean nullValue = omitted
          ? !table.hasDefault(column) : command.insertIsNull(rowIndex, source);
      if (nullValue) {
        nullMask |= 1L << column;
      }
      row.putLong(
          (column - 1) * Long.BYTES,
          omitted && table.hasDefault(column) || explicitDefault
              ? table.defaultValue(column)
              : command.insertValue(rowIndex, source));
    }
    row.putLong(table.nullMaskOffset(), nullMask);
    row.position(0);
  }

  private StatusCode bindInsertColumns() {
    for (int index = 0; index < insertSourceByColumn.length; index++) {
      insertSourceByColumn[index] = -1;
    }
    if (command.columnCount() == 0) {
      if (command.insertColumnCount() != table.columnCount()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int index = 0; index < table.columnCount(); index++) {
        insertSourceByColumn[index] = index;
      }
    } else {
      if (command.insertColumnCount() != command.columnCount()
          || command.columnCount() > table.columnCount()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int source = 0; source < command.columnCount(); source++) {
        int column = table.findColumn(command.columnName(source));
        if (column < 0 || insertSourceByColumn[column] >= 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        insertSourceByColumn[column] = source;
      }
    }
    for (int rowIndex = 0; rowIndex < command.insertRowCount(); rowIndex++) {
      int keySource = insertSourceByColumn[0];
      if (table.hasIdentity()) {
        if (keySource >= 0 && !command.insertIsDefault(rowIndex, keySource)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      } else {
        if (keySource < 0
            || command.insertIsNull(rowIndex, keySource)
            || command.insertIsDefault(rowIndex, keySource)
            || command.insertIsVarchar(rowIndex, keySource)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      }
      for (int column = 1; column < table.columnCount(); column++) {
        int source = insertSourceByColumn[column];
        if (source >= 0
            && command.insertIsDefault(rowIndex, source)
            && !table.hasDefault(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (source >= 0
            && !command.insertIsNull(rowIndex, source)
            && !command.insertIsDefault(rowIndex, source)
            && command.insertIsVarchar(rowIndex, source) != table.isVarchar(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        boolean nullValue = source < 0
            ? !table.hasDefault(column) : command.insertIsNull(rowIndex, source);
        if (nullValue && !table.isNullable(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      }
    }
    return StatusCode.OK;
  }

  private StatusCode allocateIdentityKeys() {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < command.insertRowCount(); index++) {
      status = database.nextIdentityValue(table, sequenceValue);
      if (status.isOk()) {
        generatedInsertKeys[index] = sequenceValue.value();
      }
    }
    return status;
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
    if (source.length() != definition.rowBytes()
        || !definition.isValidNullMask(
            source.length() == definition.rowBytes()
                ? source.getLong(definition.nullMaskOffset()) : 0)) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private StatusCode nextGroupAggregate(
      SqlScanCursor cursor,
      SqlScanRowResult result) {
    if (cursor.groupInputExhausted() && !cursor.hasGroupLookahead()) {
      return StatusCode.CONFLICT;
    }
    long groupValue;
    boolean groupNull;
    long inputValue;
    boolean inputNull;
    if (cursor.hasGroupLookahead()) {
      groupValue = cursor.takeGroupLookahead();
      groupNull = cursor.groupLookaheadNull();
      inputValue = cursor.groupLookaheadAggregateValue();
      inputNull = cursor.groupLookaheadAggregateNull();
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
      groupNull = groupInputNull;
      inputValue = projectedValues[1];
      inputNull = groupAggregateInputNull;
    }
    SqlCommandType aggregateType = cursor.groupAggregateType();
    long aggregate = aggregateType == SqlCommandType.GROUP_COUNT
        ? 1 : aggregateType == SqlCommandType.GROUP_COUNT_VALUE
            ? inputNull ? 0 : 1 : inputValue;
    boolean aggregateNull = aggregateType != SqlCommandType.GROUP_COUNT
        && aggregateType != SqlCommandType.GROUP_COUNT_VALUE
        && inputNull;
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
      if (groupInputNull != groupNull || !groupNull && value != groupValue) {
        cursor.setGroupLookahead(
            value,
            groupInputNull,
            projectedValues[1],
            groupAggregateInputNull);
        break;
      }
      inputValue = projectedValues[1];
      inputNull = groupAggregateInputNull;
      if (aggregateType == SqlCommandType.GROUP_COUNT
          || aggregateType == SqlCommandType.GROUP_COUNT_VALUE && !inputNull) {
        if (aggregate == Long.MAX_VALUE) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        aggregate++;
      } else if (!inputNull && aggregateNull) {
        aggregate = inputValue;
        aggregateNull = false;
      } else if (!inputNull && aggregateType == SqlCommandType.GROUP_SUM) {
        long sum = aggregate + inputValue;
        if (arithmeticOverflow(aggregate, inputValue, sum, false)) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        aggregate = sum;
      } else if (!inputNull
          && aggregateType == SqlCommandType.GROUP_MIN
          && inputValue < aggregate) {
        aggregate = inputValue;
      } else if (!inputNull
          && aggregateType == SqlCommandType.GROUP_MAX
          && inputValue > aggregate) {
        aggregate = inputValue;
      }
    }
    projectedValues[0] = groupValue;
    projectedValues[1] = aggregate;
    long nullMask = groupNull ? 1 : 0;
    if (aggregateNull) {
      nullMask |= 1L << 1;
    }
    result.set(
        groupValue,
        projectedValues,
        nullMask,
        groupProjectionVarcharMask(cursor),
        2);
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
      boolean nullValue = groupInputNull;
      if (cursor.hasDistinctValue()
          && cursor.distinctValueNull() == nullValue
          && (nullValue || cursor.distinctValue() == value)) {
        continue;
      }
      cursor.setDistinctValue(value, nullValue);
      result.set(
          value,
          projectedValues,
          nullValue ? 1 : 0,
          table.isVarchar(cursor.groupColumn()) ? 1 : 0,
          1);
      cursor.rowReturned();
      return StatusCode.OK;
    }
  }

  private StatusCode nextJoin(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      if (cursor.joinInnerScanActive()) {
        StatusCode inner = session.nextNonUniqueValueLookup(
            joinTable, cursor.joinInnerRelational(), indexed);
        if (inner == StatusCode.CONFLICT) {
          inner = session.closeScan(cursor.joinInnerRelational());
          if (inner.isOk()) {
            cursor.completeJoinInnerScan();
            inner = cursor.joinInnerRelational().reset();
          }
          if (!inner.isOk()) {
            return inner;
          }
          continue;
        }
        if (!inner.isOk()) {
          return inner;
        }
        HeapRowResult innerRow = indexed.row();
        inner = validateRow(innerRow, joinTable);
        if (!inner.isOk()) {
          return inner;
        }
        if (!matchesJoinPredicates(indexed.key(), innerRow, false)) {
          continue;
        }
        long nullMask = 0;
        for (int index = 0; index < cursor.projectedColumnCount(); index++) {
          int projection = cursor.projectedColumn(index);
          if (projection >= 0) {
            projectedValues[index] = cursor.joinOuterProjectedValue(index);
            if (cursor.joinOuterProjectedNull(index)) {
              nullMask |= 1L << index;
            }
          } else {
            int column = -projection - 1;
            projectedValues[index] = readColumn(indexed.key(), innerRow, column);
            if (isNull(innerRow, joinTable, column)) {
              nullMask |= 1L << index;
            }
          }
        }
        result.set(
            cursor.joinOuterKey(),
            projectedValues,
            nullMask,
            scanProjectionVarcharMask(cursor),
            cursor.projectedColumnCount());
        cursor.rowReturned();
        return StatusCode.OK;
      }
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
      if (!matchesJoinPredicates(outerKey, outerRow, true)) {
        continue;
      }
      if (isNull(outerRow, table, cursor.joinOuterColumn())) {
        continue;
      }
      long joinValue = readColumn(outerKey, outerRow, cursor.joinOuterColumn());
      if (cursor.joinInnerColumn() > 0 && !cursor.joinInnerUnique()) {
        for (int index = 0; index < cursor.projectedColumnCount(); index++) {
          int projection = cursor.projectedColumn(index);
          if (projection >= 0) {
            cursor.setJoinOuterProjectedValue(
                index,
                readColumn(outerKey, outerRow, projection),
                isNull(outerRow, table, projection));
          }
        }
        status = joinValue == Long.MAX_VALUE
            ? StatusCode.INVALID_EXTERNAL_INPUT
            : session.beginNonUniqueValueLookup(
                joinTable,
                cursor.joinInnerColumn(),
                joinValue,
                cursor.joinInnerRelational());
        if (status == StatusCode.CONFLICT
            || status == StatusCode.INVALID_EXTERNAL_INPUT) {
          continue;
        }
        if (!status.isOk()) {
          return status;
        }
        cursor.beginJoinInnerScan(outerKey);
        continue;
      }
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
      if (!matchesJoinPredicates(innerKey, innerRow, false)) {
        continue;
      }
      long nullMask = 0;
      for (int index = 0; index < cursor.projectedColumnCount(); index++) {
        int projection = cursor.projectedColumn(index);
        if (projection >= 0) {
          projectedValues[index] = readColumn(outerKey, outerRow, projection);
          if (isNull(outerRow, table, projection)) {
            nullMask |= 1L << index;
          }
        } else {
          int column = -projection - 1;
          projectedValues[index] = readColumn(innerKey, innerRow, column);
          if (isNull(innerRow, joinTable, column)) {
            nullMask |= 1L << index;
          }
        }
      }
      result.set(
          outerKey,
          projectedValues,
          nullMask,
          scanProjectionVarcharMask(cursor),
          cursor.projectedColumnCount());
      cursor.rowReturned();
      return StatusCode.OK;
    }
  }

  private StatusCode nextGroupValue(SqlScanCursor cursor) {
    if (cursor.sorted()) {
      int sortedRow = cursor.currentSortedRow();
      if (sortedRow < 0) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = StatusCode.OK;
      long nullMask;
      if (sortSpilled) {
        status = nextSpilledSortRow(2);
        nullMask = sortedOutputNullMask;
      } else {
        int valueStart = sortedRow * TableSchema.MAXIMUM_COLUMNS;
        projectedValues[0] = sortedValues[valueStart];
        projectedValues[1] = sortedValues[valueStart + 1];
        nullMask = sortedNullMasks[sortedRow];
      }
      if (status.isOk()) {
        cursor.advanceSortedRow();
        groupInputNull = (nullMask & 1) != 0;
        groupAggregateInputNull = cursor.groupAggregateColumn() >= 0
            && (nullMask & 1L << 1) != 0;
      }
      return status;
    }
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
      groupInputNull = isNull(source, table, column);
      int aggregateColumn = cursor.groupAggregateColumn();
      groupAggregateInputNull = aggregateColumn >= 0
          && isNull(source, table, aggregateColumn);
      projectedValues[1] = aggregateColumn < 0
          ? 0 : readColumn(primaryKey, source, aggregateColumn);
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
          && (command.isEqualityPredicate(index)
              || command.isRangePredicate(index))
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

  private StatusCode copyCorrelatedOuterRow(HeapRowResult source) {
    correlatedOuterBuffer.clear();
    correlatedOuterBuffer.limit(source.length());
    StatusCode status = source.copyTo(correlatedOuterBuffer);
    if (status.isOk()) {
      correlatedOuterBuffer.position(0);
      correlatedOuterRow.set(correlatedOuterBuffer, 0, 0, source.length());
    }
    return status;
  }

  private static boolean isNull(
      HeapRowResult source,
      TableDefinition definition,
      int column) {
    return column > 0
        && (source.getLong(definition.nullMaskOffset()) & 1L << column) != 0;
  }

  private boolean matchesPredicates(long primaryKey, HeapRowResult source) {
    if (subqueryPredicateFalse) {
      return false;
    }
    for (int index = 0; index < predicateCount; index++) {
      long value = readColumn(primaryKey, source, predicateColumns[index]);
      boolean nullValue = isNull(source, table, predicateColumns[index]);
      if (command.isNullPredicate(index)) {
        if (nullValue == command.isNullPredicateNegated(index)) {
          return false;
        }
        continue;
      }
      if (nullValue) {
        return false;
      }
      if (query.hasMembershipPredicate()
          && query.membershipPredicate() == index) {
        boolean equal = false;
        for (int candidate = 0; candidate < membershipValueCount; candidate++) {
          if (value == membershipCandidates[membershipCandidateOffset + candidate]) {
            equal = true;
            break;
          }
        }
        if (equal == query.membershipNegated()
            || !equal && membershipHasNull) {
          return false;
        }
        continue;
      }
      if (!matchesComparison(value, command, index)) {
        return false;
      }
    }
    return true;
  }

  private StatusCode prepareNestedChain() {
    StatusCode status = StatusCode.OK;
    if (hasIntermediateReference()) {
      ensureRecursiveState();
      status = bindRecursiveChain();
    }
    if (!status.isOk()) {
      return status;
    }
    if (recursiveNestedChain) {
      return recursiveRootCorrelated
          ? StatusCode.OK : evaluateRecursiveChain(0, null);
    }
    boolean correlated = false;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0;
        block--) {
      SqlCommand nested = query.block(block);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(nested);
      correlated |= nestedCorrelated;
    }
    if (status.isOk() && correlated) {
      correlatedNestedChain = true;
      return StatusCode.OK;
    }
    return status.isOk() ? evaluateNestedChain(0, null) : status;
  }

  private boolean hasIntermediateReference() {
    for (int depth = 2; depth < query.blockCount(); depth++) {
      SqlCommand nested = query.block(depth);
      for (int index = 0; index < nested.predicateCount(); index++) {
        if (!nested.isColumnPredicate(index)) {
          continue;
        }
        CharSequence qualifier = nested.predicateValueTableName(index);
        if (matchesTableQualifier(nested, qualifier)) {
          continue;
        }
        for (int scope = depth - 1; scope > 0; scope--) {
          if (matchesTableQualifier(query.block(scope), qualifier)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private void ensureRecursiveState() {
    if (recursiveMembershipValues != null) {
      return;
    }
    recursiveMembershipValues = new long[
        SqlQuery.MAXIMUM_QUERY_BLOCKS * MAXIMUM_MEMBERSHIP_VALUES];
    for (int depth = 0; depth < recursiveTables.length; depth++) {
      recursiveTables[depth] = new TableDefinition();
      recursiveCursors[depth] = new RelationalScanCursor();
      recursiveRows[depth] = new RelationalScanResult();
      recursiveRowBuffers[depth] = ByteBuffer.allocateDirect(
          TableSchema.MAXIMUM_COLUMNS * Long.BYTES);
      recursiveRowCopies[depth] = new HeapRowResult();
    }
  }

  private StatusCode bindRecursiveChain() {
    recursiveNestedChain = false;
    recursiveRootCorrelated = false;
    for (int depth = 1; depth < query.blockCount(); depth++) {
      SqlCommand nested = query.block(depth);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      TableDefinition definition = recursiveTables[depth];
      StatusCode status = session.resolveTable(nested.tableName(), definition);
      if (!status.isOk()) {
        return status;
      }
      if (nested.columnCount() != 1
          || nested.isSelectAll()
          || nested.columnTableName(0).length() > 0
              && !matchesTableQualifier(nested, nested.columnTableName(0))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int projection = nested.isNullProjection(0)
          ? NULL_PROJECTION : definition.findColumn(nested.firstColumnName());
      if (projection < 0 && projection != NULL_PROJECTION) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      recursiveProjections[depth] = projection;
      int base = depth * SqlCommand.MAXIMUM_PREDICATES;
      for (int index = 0; index < nested.predicateCount(); index++) {
        if (nested.predicateTableName(index).length() > 0
            && !matchesTableQualifier(
                nested, nested.predicateTableName(index))) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        int column = definition.findColumn(nested.predicateColumnName(index));
        if (column < 0
            || nested.isRangePredicate(index)
                && nested.predicateUpperExclusive(index)
                    <= nested.predicateLowerInclusive(index)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        int slot = base + index;
        recursivePredicateColumns[slot] = column;
        recursivePredicateValueColumns[slot] = -1;
        recursivePredicateValueScopes[slot] = -1;
        if (nested.isColumnPredicate(index)) {
          int scope = recursiveScope(
              depth, nested.predicateValueTableName(index));
          if (scope < 0) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
          TableDefinition valueDefinition = scope == 0
              ? table : recursiveTables[scope];
          int valueColumn = valueDefinition.findColumn(
              nested.predicateValueColumnName(index));
          if (valueColumn < 0) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
          recursivePredicateValueScopes[slot] = scope;
          recursivePredicateValueColumns[slot] = valueColumn;
          recursiveRootCorrelated |= scope == 0;
          recursiveNestedChain |= scope > 0 && scope < depth;
        }
      }
    }
    return StatusCode.OK;
  }

  private int recursiveScope(int depth, CharSequence qualifier) {
    if (qualifier.length() == 0) {
      return -1;
    }
    SqlCommand local = query.block(depth);
    if (matchesTableQualifier(local, qualifier)) {
      return depth;
    }
    for (int scope = depth - 1; scope > 0; scope--) {
      if (matchesTableQualifier(query.block(scope), qualifier)) {
        return scope;
      }
    }
    return matchesTableQualifier(command, qualifier) ? 0 : -1;
  }

  private StatusCode evaluateRecursiveChain(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    int resultKind = query.hasScalarPredicate()
        ? NESTED_SCALAR
        : query.hasExistencePredicate()
            ? NESTED_EXISTENCE
            : query.hasMembershipPredicate() ? NESTED_MEMBERSHIP : 0;
    if (resultKind == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = evaluateRecursiveBlock(
        1, resultKind, outerPrimaryKey, outerSource);
    if (!status.isOk()) {
      return status;
    }
    if (resultKind == NESTED_SCALAR) {
      subqueryPredicateFalse = recursiveScalarNulls[1];
      if (!subqueryPredicateFalse) {
        status = query.bindScalarValue(command, recursiveScalarValues[1]);
      }
    } else if (resultKind == NESTED_EXISTENCE) {
      subqueryPredicateFalse = query.existenceNegated()
          ? recursiveExistenceResults[1] : !recursiveExistenceResults[1];
    } else {
      subqueryPredicateFalse = false;
      membershipCandidates = recursiveMembershipValues;
      membershipCandidateOffset = MAXIMUM_MEMBERSHIP_VALUES;
      membershipValueCount = recursiveMembershipCounts[1];
      membershipHasNull = recursiveMembershipNulls[1];
    }
    return status;
  }

  private StatusCode evaluateRecursiveBlock(
      int depth,
      int resultKind,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    recursiveScalarNulls[depth] = true;
    recursiveScalarValues[depth] = 0;
    recursiveExistenceResults[depth] = false;
    recursiveMembershipCounts[depth] = 0;
    recursiveMembershipNulls[depth] = false;
    SqlCommand nested = query.block(depth);
    if (nested.rowLimit() == 0) {
      return StatusCode.OK;
    }
    TableDefinition definition = recursiveTables[depth];
    RelationalScanCursor cursor = recursiveCursors[depth];
    RelationalScanResult rowResult = recursiveRows[depth];
    StatusCode status = session.beginScan(definition, cursor);
    boolean cursorActive = status.isOk();
    long matchedRows = 0;
    while (status.isOk()) {
      status = session.nextScan(cursor, rowResult);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      HeapRowResult source = rowResult.row();
      long primaryKey = rowResult.key();
      if (status.isOk()) {
        status = validateRow(source, definition);
      }
      if (status.isOk()
          && !matchesRecursivePredicates(
              depth, primaryKey, source, outerPrimaryKey, outerSource)) {
        continue;
      }
      if (status.isOk() && depth + 1 < query.blockCount()) {
        status = copyRecursiveRow(depth, primaryKey, source);
        if (status.isOk()) {
          source = recursiveRowCopies[depth];
          int childKind = recursiveResultKind(depth);
          status = childKind == 0
              ? StatusCode.INVALID_EXTERNAL_INPUT
              : evaluateRecursiveBlock(
                  depth + 1, childKind, outerPrimaryKey, outerSource);
        }
        if (status.isOk()
            && !matchesRecursiveChild(depth, primaryKey, source)) {
          continue;
        }
      }
      if (!status.isOk()) {
        break;
      }
      matchedRows++;
      int projection = recursiveProjections[depth];
      if (resultKind == NESTED_EXISTENCE) {
        recursiveExistenceResults[depth] = true;
        break;
      }
      if (resultKind == NESTED_SCALAR) {
        if (matchedRows > 1) {
          status = StatusCode.CARDINALITY_VIOLATION;
        } else if (projection != NULL_PROJECTION
            && !isNull(source, definition, projection)) {
          recursiveScalarNulls[depth] = false;
          recursiveScalarValues[depth] = readColumn(
              primaryKey, source, projection);
        }
      } else if (projection == NULL_PROJECTION
          || isNull(source, definition, projection)) {
        recursiveMembershipNulls[depth] = true;
      } else {
        int count = recursiveMembershipCounts[depth];
        if (count >= MAXIMUM_MEMBERSHIP_VALUES) {
          status = StatusCode.RESOURCE_EXHAUSTED;
        } else {
          recursiveMembershipValues[
              depth * MAXIMUM_MEMBERSHIP_VALUES + count] = readColumn(
                  primaryKey, source, projection);
          recursiveMembershipCounts[depth] = count + 1;
        }
      }
      if (status.isOk() && matchedRows >= nested.rowLimit()) {
        break;
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(cursor);
      if (close.isOk()) {
        cursor.reset();
        rowResult.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private int recursiveResultKind(int block) {
    return query.hasScalarPredicate(block)
        ? NESTED_SCALAR
        : query.hasExistencePredicate(block)
            ? NESTED_EXISTENCE
            : query.hasMembershipPredicate(block) ? NESTED_MEMBERSHIP : 0;
  }

  private StatusCode copyRecursiveRow(
      int depth,
      long primaryKey,
      HeapRowResult source) {
    ByteBuffer target = recursiveRowBuffers[depth];
    target.clear();
    target.limit(source.length());
    StatusCode status = source.copyTo(target);
    if (status.isOk()) {
      target.position(0);
      recursiveRowCopies[depth].set(target, 0, 0, source.length());
      recursiveKeys[depth] = primaryKey;
    }
    return status;
  }

  private boolean matchesRecursivePredicates(
      int depth,
      long primaryKey,
      HeapRowResult source,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    SqlCommand nested = query.block(depth);
    TableDefinition definition = recursiveTables[depth];
    int skipped = query.hasScalarPredicate(depth)
        ? query.scalarPredicate(depth)
        : query.hasMembershipPredicate(depth)
            ? query.membershipPredicate(depth) : -1;
    int base = depth * SqlCommand.MAXIMUM_PREDICATES;
    for (int index = 0; index < nested.predicateCount(); index++) {
      if (index == skipped) {
        continue;
      }
      int slot = base + index;
      int column = recursivePredicateColumns[slot];
      long value = readColumn(primaryKey, source, column);
      boolean nullValue = isNull(source, definition, column);
      if (nested.isNullPredicate(index)) {
        if (nullValue == nested.isNullPredicateNegated(index)) {
          return false;
        }
        continue;
      }
      if (nullValue) {
        return false;
      }
      if (nested.isColumnPredicate(index)) {
        int scope = recursivePredicateValueScopes[slot];
        int valueColumn = recursivePredicateValueColumns[slot];
        HeapRowResult valueSource = scope == 0
            ? outerSource : scope == depth
                ? source : recursiveRowCopies[scope];
        TableDefinition valueDefinition = scope == 0
            ? table : recursiveTables[scope];
        long valueKey = scope == 0
            ? outerPrimaryKey : scope == depth
                ? primaryKey : recursiveKeys[scope];
        if (valueSource == null
            || isNull(valueSource, valueDefinition, valueColumn)
            || value != readColumn(valueKey, valueSource, valueColumn)) {
          return false;
        }
        continue;
      }
      if (!matchesComparison(value, nested, index)) {
        return false;
      }
    }
    return true;
  }

  private boolean matchesRecursiveChild(
      int depth,
      long primaryKey,
      HeapRowResult source) {
    int child = depth + 1;
    if (query.hasExistencePredicate(depth)) {
      boolean exists = recursiveExistenceResults[child];
      return query.existenceNegated(depth) ? !exists : exists;
    }
    int predicate = query.hasScalarPredicate(depth)
        ? query.scalarPredicate(depth) : query.membershipPredicate(depth);
    int column = recursivePredicateColumns[
        depth * SqlCommand.MAXIMUM_PREDICATES + predicate];
    TableDefinition definition = recursiveTables[depth];
    if (isNull(source, definition, column)) {
      return false;
    }
    long value = readColumn(primaryKey, source, column);
    if (query.hasScalarPredicate(depth)) {
      return !recursiveScalarNulls[child]
          && value == recursiveScalarValues[child];
    }
    int count = recursiveMembershipCounts[child];
    int offset = child * MAXIMUM_MEMBERSHIP_VALUES;
    boolean equal = false;
    for (int index = 0; index < count; index++) {
      if (value == recursiveMembershipValues[offset + index]) {
        equal = true;
        break;
      }
    }
    return equal != query.membershipNegated(depth)
        && (equal || !recursiveMembershipNulls[child]);
  }

  private StatusCode evaluateNestedChain(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    StatusCode status = StatusCode.OK;
    boolean commandEnabled = true;
    long[] candidates = membershipValues;
    int candidateCount = 0;
    boolean candidateHasNull = false;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0;
        block--) {
      SqlCommand nested = query.block(block);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(nested);
      if (status.isOk() && nestedCorrelated && outerSource == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int parent = block - 1;
      int resultKind = query.hasScalarPredicate(parent)
          ? NESTED_SCALAR
          : query.hasExistencePredicate(parent)
              ? NESTED_EXISTENCE
              : query.hasMembershipPredicate(parent)
                  ? NESTED_MEMBERSHIP : 0;
      if (status.isOk() && resultKind == 0) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      long[] output = candidates == membershipValues
          ? membershipScratchValues : membershipValues;
      membershipValueCount = 0;
      membershipHasNull = false;
      if (status.isOk()) {
        status = evaluateNestedRows(
            nested,
            commandEnabled,
            resultKind,
            output,
            outerPrimaryKey,
            outerSource,
            query.membershipPredicate(block),
            query.membershipNegated(block),
            candidates,
            candidateCount,
            candidateHasNull);
      }
      if (!status.isOk()) {
        break;
      }
      SqlCommand destination = parent == 0 ? command : query.block(parent);
      if (resultKind == NESTED_SCALAR) {
        commandEnabled = !scalarResultNull;
        if (commandEnabled) {
          status = query.bindScalarValue(
              destination, parent, scalarResultValue);
        }
      } else if (resultKind == NESTED_EXISTENCE) {
        commandEnabled = query.existenceNegated(parent)
            ? !existenceResult : existenceResult;
      } else {
        commandEnabled = true;
        candidates = output;
        candidateCount = membershipValueCount;
        candidateHasNull = membershipHasNull;
      }
    }
    if (status.isOk()) {
      subqueryPredicateFalse = !commandEnabled;
      if (query.hasMembershipPredicate()) {
        membershipCandidates = candidates;
        membershipValueCount = candidateCount;
        membershipHasNull = candidateHasNull;
      }
    }
    return status;
  }

  private StatusCode evaluateNestedRows(
      SqlCommand nested,
      boolean commandEnabled,
      int resultKind,
      long[] output,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      int nestedMembershipPredicate,
      boolean nestedMembershipNegated,
      long[] input,
      int inputCount,
      boolean inputHasNull) {
    scalarResultNull = true;
    scalarResultValue = 0;
    existenceResult = false;
    if (!commandEnabled || nested.rowLimit() == 0) {
      return StatusCode.OK;
    }
    StatusCode status = session.beginScan(scalarTable, scalarCursor);
    boolean cursorActive = status.isOk();
    long matchedRows = 0;
    while (status.isOk()) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), scalarTable);
      }
      if (status.isOk()
          && !matchesScalarPredicates(
              nested,
              scalarRow.key(),
              scalarRow.row(),
              outerPrimaryKey,
              outerSource,
              nestedMembershipPredicate,
              nestedMembershipNegated,
              input,
              inputCount,
              inputHasNull)) {
        continue;
      }
      if (!status.isOk()) {
        break;
      }
      matchedRows++;
      if (resultKind == NESTED_EXISTENCE) {
        existenceResult = true;
        break;
      }
      if (resultKind == NESTED_SCALAR) {
        if (matchedRows > 1) {
          status = StatusCode.CARDINALITY_VIOLATION;
        } else if (nestedProjection != NULL_PROJECTION
            && !isNull(scalarRow.row(), scalarTable, nestedProjection)) {
          scalarResultNull = false;
          scalarResultValue = readColumn(
              scalarRow.key(), scalarRow.row(), nestedProjection);
        }
      } else if (nestedProjection == NULL_PROJECTION
          || isNull(scalarRow.row(), scalarTable, nestedProjection)) {
        membershipHasNull = true;
      } else if (membershipValueCount >= output.length) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      } else {
        output[membershipValueCount++] = readColumn(
            scalarRow.key(), scalarRow.row(), nestedProjection);
      }
      if (status.isOk() && matchedRows >= nested.rowLimit()) {
        break;
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(scalarCursor);
      if (close.isOk()) {
        scalarCursor.reset();
        scalarRow.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode evaluateScalarPredicate() {
    StatusCode status = StatusCode.OK;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0 && !subqueryPredicateFalse;
        block--) {
      SqlCommand scalar = query.block(block);
      if (scalar == null || scalar.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(scalar);
      if (status.isOk() && nestedCorrelated) {
        if (query.blockCount() != 2) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        correlatedScalar = true;
        return StatusCode.OK;
      }
      if (status.isOk()) {
        SqlCommand destination = block == 1 ? command : query.block(block - 1);
        status = evaluateScalarRows(
            scalar, 0, null, destination, block - 1);
      }
    }
    return status;
  }

  private StatusCode evaluateCorrelatedScalar(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    SqlCommand scalar = query.scalarCommand();
    return scalar == null
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : evaluateScalarRows(
            scalar, outerPrimaryKey, outerSource, command, 0);
  }

  private StatusCode evaluateScalarRows(
      SqlCommand scalar,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      SqlCommand destination,
      int destinationBlock) {
    StatusCode status = StatusCode.OK;
    if (scalar.rowLimit() == 0) {
      subqueryPredicateFalse = true;
      return StatusCode.OK;
    }
    status = session.beginScan(scalarTable, scalarCursor);
    boolean cursorActive = status.isOk();
    int rows = 0;
    long value = 0;
    while (status.isOk()) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), scalarTable);
      }
      if (status.isOk()
          && !matchesScalarPredicates(
              scalar,
              scalarRow.key(),
              scalarRow.row(),
              outerPrimaryKey,
              outerSource,
              -1,
              false,
              membershipScratchValues,
              0,
              false)) {
        continue;
      }
      if (status.isOk()) {
        rows++;
        if (rows > 1) {
          status = StatusCode.CARDINALITY_VIOLATION;
        } else {
          if (nestedProjection == NULL_PROJECTION
              || isNull(scalarRow.row(), scalarTable, nestedProjection)) {
            subqueryPredicateFalse = true;
          } else {
            value = readColumn(scalarRow.key(), scalarRow.row(), nestedProjection);
          }
          if (scalar.rowLimit() == 1) {
            break;
          }
        }
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(scalarCursor);
      if (close.isOk()) {
        scalarCursor.reset();
        scalarRow.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    if (status.isOk() && rows == 0) {
      subqueryPredicateFalse = true;
    } else if (status.isOk() && !subqueryPredicateFalse) {
      status = query.bindScalarValue(destination, destinationBlock, value);
    }
    return status;
  }

  private StatusCode evaluateExistencePredicate() {
    StatusCode status = StatusCode.OK;
    boolean nestedPredicateTrue = true;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0;
        block--) {
      SqlCommand nested = query.block(block);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(nested);
      if (status.isOk() && nestedCorrelated) {
        if (query.blockCount() != 2) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        correlatedExistence = true;
        return StatusCode.OK;
      }
      if (status.isOk()) {
        if (nestedPredicateTrue) {
          status = evaluateExistenceRows(nested, 0, null);
        } else {
          existenceResult = false;
        }
      }
      if (status.isOk()) {
        nestedPredicateTrue = query.existenceNegated(block - 1)
            ? !existenceResult : existenceResult;
      }
    }
    if (status.isOk()) {
      subqueryPredicateFalse = !nestedPredicateTrue;
    }
    return status;
  }

  private StatusCode evaluateCorrelatedExistence(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    SqlCommand nested = query.existenceCommand();
    if (nested == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = evaluateExistenceRows(
        nested, outerPrimaryKey, outerSource);
    if (status.isOk()) {
      subqueryPredicateFalse = query.existenceNegated()
          ? existenceResult : !existenceResult;
    }
    return status;
  }

  private StatusCode evaluateExistenceRows(
      SqlCommand nested,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    StatusCode status = StatusCode.OK;
    existenceResult = false;
    if (status.isOk() && nested.rowLimit() > 0) {
      status = session.beginScan(scalarTable, scalarCursor);
    }
    boolean cursorActive = status.isOk() && nested.rowLimit() > 0;
    while (status.isOk() && cursorActive) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), scalarTable);
      }
      if (status.isOk()
          && matchesScalarPredicates(
              nested,
              scalarRow.key(),
              scalarRow.row(),
              outerPrimaryKey,
              outerSource,
              -1,
              false,
              membershipScratchValues,
              0,
              false)) {
        existenceResult = true;
        break;
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(scalarCursor);
      if (close.isOk()) {
        scalarCursor.reset();
        scalarRow.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode evaluateMembershipPredicate() {
    StatusCode status = StatusCode.OK;
    long[] input = membershipValues;
    int inputCount = 0;
    boolean inputHasNull = false;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0;
        block--) {
      SqlCommand nested = query.block(block);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(nested);
      if (status.isOk() && nestedCorrelated) {
        if (query.blockCount() != 2) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        correlatedMembership = true;
        membershipCandidates = membershipValues;
        return StatusCode.OK;
      }
      long[] output = input == membershipValues
          ? membershipScratchValues : membershipValues;
      membershipValueCount = 0;
      membershipHasNull = false;
      if (status.isOk()) {
        status = evaluateMembershipRows(
            nested,
            0,
            null,
            output,
            query.membershipPredicate(block),
            query.membershipNegated(block),
            input,
            inputCount,
            inputHasNull);
      }
      input = output;
      inputCount = membershipValueCount;
      inputHasNull = membershipHasNull;
    }
    membershipCandidates = input;
    return status;
  }

  private StatusCode evaluateCorrelatedMembership(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    SqlCommand nested = query.membershipCommand();
    if (nested == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    membershipCandidates = membershipValues;
    return evaluateMembershipRows(
        nested,
        outerPrimaryKey,
        outerSource,
        membershipValues,
        -1,
        false,
        membershipScratchValues,
        0,
        false);
  }

  private StatusCode evaluateMembershipRows(
      SqlCommand nested,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      long[] output,
      int nestedMembershipPredicate,
      boolean nestedMembershipNegated,
      long[] input,
      int inputCount,
      boolean inputHasNull) {
    if (nested.rowLimit() == 0) {
      return StatusCode.OK;
    }
    StatusCode status = session.beginScan(scalarTable, scalarCursor);
    boolean cursorActive = status.isOk();
    long matchedRows = 0;
    while (status.isOk()) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), scalarTable);
      }
      if (status.isOk()
          && !matchesScalarPredicates(
              nested,
              scalarRow.key(),
              scalarRow.row(),
              outerPrimaryKey,
              outerSource,
              nestedMembershipPredicate,
              nestedMembershipNegated,
              input,
              inputCount,
              inputHasNull)) {
        continue;
      }
      if (status.isOk()) {
        matchedRows++;
        if (nestedProjection == NULL_PROJECTION
            || isNull(scalarRow.row(), scalarTable, nestedProjection)) {
          membershipHasNull = true;
        } else if (membershipValueCount >= output.length) {
          status = StatusCode.RESOURCE_EXHAUSTED;
        } else {
          output[membershipValueCount++] = readColumn(
              scalarRow.key(), scalarRow.row(), nestedProjection);
        }
        if (status.isOk() && matchedRows >= nested.rowLimit()) {
          break;
        }
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(scalarCursor);
      if (close.isOk()) {
        scalarCursor.reset();
        scalarRow.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode bindNestedCommand(SqlCommand nested) {
    nestedCorrelated = false;
    if (nested.columnCount() != 1 || nested.isSelectAll()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.resolveTable(nested.tableName(), scalarTable);
    if (status.isOk()
        && nested.columnTableName(0).length() > 0
        && !matchesTableQualifier(nested, nested.columnTableName(0))) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    nestedProjection = status.isOk() && nested.isNullProjection(0)
        ? NULL_PROJECTION
        : status.isOk() ? scalarTable.findColumn(nested.firstColumnName()) : -1;
    if (status.isOk()
        && nestedProjection < 0
        && nestedProjection != NULL_PROJECTION) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; status.isOk() && index < nested.predicateCount(); index++) {
      if (nested.predicateTableName(index).length() > 0
          && !matchesTableQualifier(nested, nested.predicateTableName(index))) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
        break;
      }
      int column = scalarTable.findColumn(nested.predicateColumnName(index));
      if (column < 0
          || nested.isRangePredicate(index)
              && nested.predicateUpperExclusive(index)
                  <= nested.predicateLowerInclusive(index)) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
        break;
      }
      scalarPredicateColumns[index] = column;
      scalarPredicateValueColumns[index] = -1;
      scalarPredicateValueOuter[index] = false;
      if (status.isOk() && nested.isColumnPredicate(index)) {
        CharSequence valueTable = nested.predicateValueTableName(index);
        int valueColumn;
        if (valueTable.length() == 0) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        } else if (matchesTableQualifier(nested, valueTable)) {
          valueColumn = scalarTable.findColumn(
              nested.predicateValueColumnName(index));
        } else if (matchesTableQualifier(command, valueTable)) {
          valueColumn = table.findColumn(nested.predicateValueColumnName(index));
          scalarPredicateValueOuter[index] = true;
          nestedCorrelated = true;
        } else {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        }
        if (valueColumn < 0) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        }
        scalarPredicateValueColumns[index] = valueColumn;
      }
    }
    return status;
  }

  private boolean matchesScalarPredicates(
      SqlCommand scalar,
      long primaryKey,
      HeapRowResult source,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      int nestedMembershipPredicate,
      boolean nestedMembershipNegated,
      long[] nestedMembershipValues,
      int nestedMembershipValueCount,
      boolean nestedMembershipHasNull) {
    for (int index = 0; index < scalar.predicateCount(); index++) {
      long value = readColumn(primaryKey, source, scalarPredicateColumns[index]);
      boolean nullValue = isNull(
          source, scalarTable, scalarPredicateColumns[index]);
      if (scalar.isNullPredicate(index)) {
        if (nullValue == scalar.isNullPredicateNegated(index)) {
          return false;
        }
        continue;
      }
      if (nullValue) {
        return false;
      }
      if (index == nestedMembershipPredicate) {
        boolean equal = false;
        for (int candidate = 0;
            candidate < nestedMembershipValueCount;
            candidate++) {
          if (value == nestedMembershipValues[candidate]) {
            equal = true;
            break;
          }
        }
        if (equal == nestedMembershipNegated
            || !equal && nestedMembershipHasNull) {
          return false;
        }
        continue;
      }
      if (scalar.isColumnPredicate(index)) {
        boolean outer = scalarPredicateValueOuter[index];
        HeapRowResult valueSource = outer ? outerSource : source;
        TableDefinition valueTable = outer ? table : scalarTable;
        int valueColumn = scalarPredicateValueColumns[index];
        if (valueSource == null
            || isNull(valueSource, valueTable, valueColumn)
            || value != readColumn(
                outer ? outerPrimaryKey : primaryKey,
                valueSource,
                valueColumn)) {
          return false;
        }
        continue;
      }
      if (!matchesComparison(value, scalar, index)) {
        return false;
      }
    }
    return true;
  }

  private boolean matchesJoinPredicates(
      long primaryKey,
      HeapRowResult source,
      boolean outer) {
    for (int index = 0; index < predicateCount; index++) {
      int descriptor = predicateColumns[index];
      if (outer != (descriptor >= 0)) {
        continue;
      }
      int column = outer ? descriptor : -descriptor - 1;
      TableDefinition definition = outer ? table : joinTable;
      boolean nullValue = isNull(source, definition, column);
      if (command.isNullPredicate(index)) {
        if (nullValue == command.isNullPredicateNegated(index)) {
          return false;
        }
        continue;
      }
      if (nullValue) {
        return false;
      }
      long value = readColumn(primaryKey, source, column);
      if (!matchesComparison(value, command, index)) {
        return false;
      }
    }
    return true;
  }

  private boolean accessEquality() {
    return accessPredicate >= 0 && command.isEqualityPredicate(accessPredicate);
  }

  private static boolean matchesComparison(
      long actual,
      SqlCommand source,
      int predicate) {
    long expected = source.predicateValue(predicate);
    SqlComparison comparison = source.comparison(predicate);
    return switch (comparison) {
      case EQUAL -> actual == expected;
      case NOT_EQUAL -> actual != expected;
      case LESS_THAN -> actual < expected;
      case LESS_OR_EQUAL -> actual <= expected;
      case GREATER_THAN -> actual > expected;
      case GREATER_OR_EQUAL -> actual >= expected;
      case HALF_OPEN_RANGE ->
        actual >= source.predicateLowerInclusive(predicate)
            && actual < source.predicateUpperExclusive(predicate);
      case IN, NOT_IN -> matchesLiteralMembership(actual, source, predicate);
    };
  }

  private static boolean matchesLiteralMembership(
      long actual,
      SqlCommand source,
      int predicate) {
    boolean equal = false;
    int lower = 0;
    int upper = source.literalMembershipCount(predicate);
    while (lower < upper) {
      int middle = (lower + upper) >>> 1;
      long candidate = source.literalMembershipValue(predicate, middle);
      if (candidate < actual) {
        lower = middle + 1;
      } else if (candidate > actual) {
        upper = middle;
      } else {
        equal = true;
        break;
      }
    }
    return source.comparison(predicate) == SqlComparison.IN
        ? equal
        : !equal && !source.literalMembershipHasNull(predicate);
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

  private static boolean matchesTableQualifier(
      SqlCommand qualified,
      CharSequence name) {
    return sameName(name, qualified.tableName())
        || qualified.tableAlias().length() > 0
            && sameName(name, qualified.tableAlias());
  }

  private static boolean matchesJoinTableQualifier(
      SqlCommand qualified,
      CharSequence name) {
    return sameName(name, qualified.joinTableName())
        || qualified.joinTableAlias().length() > 0
            && sameName(name, qualified.joinTableAlias());
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
        destination[index] = column == NULL_PROJECTION
            ? 0 : readColumn(primaryKey, source, column);
      }
    }
    return status;
  }

  private long projectScanRow(
      long primaryKey,
      HeapRowResult source,
      SqlScanCursor cursor,
      long[] destination) {
    long nullMask = 0;
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int column = cursor.projectedColumn(index);
      if (column == NULL_PROJECTION) {
        destination[index] = 0;
        nullMask |= 1L << index;
      } else {
        destination[index] = readColumn(primaryKey, source, column);
        if (isNull(source, table, column)) {
          nullMask |= 1L << index;
        }
      }
    }
    return nullMask;
  }

  private long projectionNullMask(
      HeapRowResult source,
      TableDefinition definition,
      int[] columns,
      int columnCount) {
    long nullMask = 0;
    for (int index = 0; index < columnCount; index++) {
      if (columns[index] == NULL_PROJECTION
          || isNull(source, definition, columns[index])) {
        nullMask |= 1L << index;
      }
    }
    return nullMask;
  }

  private StatusCode materializeSortedScan(
      SqlScanCursor cursor,
      boolean valueIndex,
      int orderColumn) {
    ensureSortWorkspace();
    sortedRowCount = 0;
    sortedTotalRows = 0;
    sortRunCount = 0;
    sortSpillWriteOffset = 0;
    sortSpilled = false;
    StatusCode status = closeSortSpill();
    while (status.isOk()) {
      long primaryKey;
      HeapRowResult source;
      if (valueIndex) {
        status = session.nextValueScan(
            table, cursor.relational(), aggregateRow, indexed);
        primaryKey = indexed.key();
        source = indexed.row();
      } else {
        status = session.nextScan(cursor.relational(), aggregateRow);
        primaryKey = aggregateRow.key();
        source = aggregateRow.row();
      }
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(source);
      }
      if (correlatedScalar || correlatedExistence) {
        subqueryPredicateFalse = false;
      }
      if (status.isOk() && recursiveNestedChain && recursiveRootCorrelated) {
        subqueryPredicateFalse = false;
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateRecursiveChain(primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
        if (status.isOk() && subqueryPredicateFalse) {
          continue;
        }
      }
      if (status.isOk() && correlatedNestedChain) {
        subqueryPredicateFalse = false;
        membershipValueCount = 0;
        membershipHasNull = false;
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateNestedChain(primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
        if (status.isOk() && subqueryPredicateFalse) {
          continue;
        }
      }
      if (status.isOk() && correlatedScalar) {
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateCorrelatedScalar(primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
        if (status.isOk() && subqueryPredicateFalse) {
          continue;
        }
      }
      if (status.isOk() && correlatedMembership) {
        membershipValueCount = 0;
        membershipHasNull = false;
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateCorrelatedMembership(
              primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
      }
      if (status.isOk() && !matchesPredicates(primaryKey, source)) {
        continue;
      }
      if (status.isOk() && correlatedExistence) {
        status = copyCorrelatedOuterRow(source);
        if (status.isOk()) {
          status = evaluateCorrelatedExistence(
              primaryKey, correlatedOuterRow);
          source = correlatedOuterRow;
        }
        if (status.isOk() && subqueryPredicateFalse) {
          continue;
        }
      }
      if (status.isOk() && sortedRowCount >= MAXIMUM_SORT_ROWS) {
        status = spillSortedRun();
      }
      if (status.isOk()) {
        int rowIndex = sortedRowCount++;
        sortedTotalRows++;
        sortedKeys[rowIndex] = readColumn(primaryKey, source, orderColumn);
        sortedKeyNulls[rowIndex] = isNull(source, table, orderColumn);
        sortedPrimaryKeys[rowIndex] = primaryKey;
        int valueStart = rowIndex * TableSchema.MAXIMUM_COLUMNS;
        long nullMask = 0;
        for (int index = 0; index < projectedColumnCount; index++) {
          int projection = projectedColumns[index];
          sortedValues[valueStart + index] = projection == NULL_PROJECTION
              ? 0 : readColumn(primaryKey, source, projection);
          if (projection == NULL_PROJECTION || isNull(source, table, projection)) {
            nullMask |= 1L << index;
          }
        }
        sortedNullMasks[rowIndex] = nullMask;
      }
    }
    StatusCode close = session.closeScan(cursor.relational());
    if (!close.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      if (sortSpilled) {
        status = spillSortedRun();
        if (status.isOk()) {
          status = initializeSortMerge();
        }
      } else {
        sortMaterializedRows();
      }
    }
    if (!status.isOk()) {
      StatusCode cleanup = closeSortSpill();
      if (!cleanup.isOk()) {
        return cleanup;
      }
    }
    return status;
  }

  private StatusCode spillSortedRun() {
    if (sortedRowCount <= 0) {
      return StatusCode.OK;
    }
    if (sortRunCount >= MAXIMUM_SORT_RUNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = openSortSpill();
    if (!status.isOk()) {
      return status;
    }
    sortMaterializedRows();
    int run = sortRunCount;
    sortRunOffsets[run] = sortSpillWriteOffset;
    sortRunRowCounts[run] = sortedRowCount;
    int dataBytes = (projectedColumnCount + 3) * Long.BYTES;
    int recordBytes = dataBytes + Integer.BYTES;
    for (int rowIndex = 0; rowIndex < sortedRowCount; rowIndex++) {
      sortRecord.clear();
      sortRecord.limit(recordBytes);
      sortRecord.putLong(sortedKeys[rowIndex]);
      sortRecord.putLong(sortedPrimaryKeys[rowIndex]);
      sortRecord.putLong(
          sortedNullMasks[rowIndex]
              | (sortedKeyNulls[rowIndex] ? Long.MIN_VALUE : 0));
      int valueStart = rowIndex * TableSchema.MAXIMUM_COLUMNS;
      for (int index = 0; index < projectedColumnCount; index++) {
        sortRecord.putLong(sortedValues[valueStart + index]);
      }
      sortRecord.putInt(sortRecordChecksum(dataBytes));
      sortRecord.flip();
      status = writeSortRecord();
      if (!status.isOk()) {
        return status;
      }
    }
    sortRunCount++;
    sortSpilled = true;
    sortedRowCount = 0;
    return StatusCode.OK;
  }

  private StatusCode openSortSpill() {
    if (sortSpillChannel != null) {
      return StatusCode.OK;
    }
    try {
      sortSpillPath = Files.createTempFile("river-sort-", ".run");
      sortSpillChannel = FileChannel.open(
          sortSpillPath,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE);
      return StatusCode.OK;
    } catch (IOException failure) {
      sortSpillChannel = null;
      return StatusCode.IO_FAILURE;
    }
  }

  private StatusCode writeSortRecord() {
    try {
      while (sortRecord.hasRemaining()) {
        int written = sortSpillChannel.write(sortRecord, sortSpillWriteOffset);
        if (written <= 0) {
          return StatusCode.IO_FAILURE;
        }
        sortSpillWriteOffset += written;
      }
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  private StatusCode initializeSortMerge() {
    for (int run = 0; run < sortRunCount; run++) {
      sortRunReadOffsets[run] = sortRunOffsets[run];
      sortRunRowsRemaining[run] = sortRunRowCounts[run];
      sortRunActive[run] = false;
      StatusCode status = readSortRunRow(run);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode nextSpilledSortRow(int columnCount) {
    int selected = -1;
    for (int run = 0; run < sortRunCount; run++) {
      if (sortRunActive[run]
          && (selected < 0 || compareSortMergeRows(run, selected) < 0)) {
        selected = run;
      }
    }
    if (selected < 0) {
      return StatusCode.CORRUPTION;
    }
    sortedOutputPrimaryKey = sortMergePrimaryKeys[selected];
    sortedOutputNullMask = sortMergeNullMasks[selected];
    int valueStart = selected * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < columnCount; index++) {
      projectedValues[index] = sortMergeValues[valueStart + index];
    }
    return readSortRunRow(selected);
  }

  private int compareSortMergeRows(int left, int right) {
    int comparison;
    if (sortMergeKeyNulls[left] != sortMergeKeyNulls[right]) {
      comparison = sortMergeKeyNulls[left] ? -1 : 1;
    } else {
      comparison = Long.compare(sortMergeKeys[left], sortMergeKeys[right]);
    }
    if (comparison == 0) {
      comparison = Long.compare(
          sortMergePrimaryKeys[left], sortMergePrimaryKeys[right]);
    }
    return command.isDescendingOrder() ? -comparison : comparison;
  }

  private StatusCode readSortRunRow(int run) {
    if (sortRunRowsRemaining[run] <= 0) {
      sortRunActive[run] = false;
      return StatusCode.OK;
    }
    int dataBytes = (projectedColumnCount + 3) * Long.BYTES;
    int recordBytes = dataBytes + Integer.BYTES;
    sortRecord.clear();
    sortRecord.limit(recordBytes);
    long offset = sortRunReadOffsets[run];
    try {
      while (sortRecord.hasRemaining()) {
        int read = sortSpillChannel.read(sortRecord, offset);
        if (read <= 0) {
          return read < 0 ? StatusCode.CORRUPTION : StatusCode.IO_FAILURE;
        }
        offset += read;
      }
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
    sortRecord.flip();
    int storedChecksum = sortRecord.getInt(dataBytes);
    if (storedChecksum != sortRecordChecksum(dataBytes)) {
      return StatusCode.CORRUPTION;
    }
    sortMergeKeys[run] = sortRecord.getLong();
    sortMergePrimaryKeys[run] = sortRecord.getLong();
    long nullInfo = sortRecord.getLong();
    sortMergeNullMasks[run] = nullInfo & ~Long.MIN_VALUE;
    sortMergeKeyNulls[run] = (nullInfo & Long.MIN_VALUE) != 0;
    int valueStart = run * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < projectedColumnCount; index++) {
      sortMergeValues[valueStart + index] = sortRecord.getLong();
    }
    sortRunReadOffsets[run] = offset;
    sortRunRowsRemaining[run]--;
    sortRunActive[run] = true;
    return StatusCode.OK;
  }

  private int sortRecordChecksum(int length) {
    sortChecksum.reset();
    for (int index = 0; index < length; index++) {
      sortChecksum.update(sortRecord.get(index));
    }
    return (int) sortChecksum.getValue();
  }

  private StatusCode closeSortSpill() {
    if (sortSpillChannel != null) {
      try {
        sortSpillChannel.close();
        sortSpillChannel = null;
      } catch (IOException failure) {
        return StatusCode.IO_FAILURE;
      }
    }
    if (sortSpillPath != null) {
      try {
        Files.deleteIfExists(sortSpillPath);
        sortSpillPath = null;
      } catch (IOException failure) {
        return StatusCode.IO_FAILURE;
      }
    }
    sortSpilled = false;
    sortRunCount = 0;
    return StatusCode.OK;
  }

  private void ensureSortWorkspace() {
    if (sortedKeys != null) {
      return;
    }
    sortedKeys = new long[MAXIMUM_SORT_ROWS];
    sortedPrimaryKeys = new long[MAXIMUM_SORT_ROWS];
    sortedValues = new long[MAXIMUM_SORT_ROWS * TableSchema.MAXIMUM_COLUMNS];
    sortedNullMasks = new long[MAXIMUM_SORT_ROWS];
    sortedKeyNulls = new boolean[MAXIMUM_SORT_ROWS];
  }

  private void sortMaterializedRows() {
    for (int root = sortedRowCount / 2 - 1; root >= 0; root--) {
      siftSortedRow(root, sortedRowCount);
    }
    for (int end = sortedRowCount - 1; end > 0; end--) {
      swapSortedRows(0, end);
      siftSortedRow(0, end);
    }
  }

  private void siftSortedRow(int root, int length) {
    int current = root;
    while (current * 2 + 1 < length) {
      int child = current * 2 + 1;
      if (child + 1 < length && compareSortedRows(child, child + 1) < 0) {
        child++;
      }
      if (compareSortedRows(current, child) >= 0) {
        return;
      }
      swapSortedRows(current, child);
      current = child;
    }
  }

  private int compareSortedRows(int left, int right) {
    int comparison;
    if (sortedKeyNulls[left] != sortedKeyNulls[right]) {
      comparison = sortedKeyNulls[left] ? -1 : 1;
    } else {
      comparison = Long.compare(sortedKeys[left], sortedKeys[right]);
    }
    if (comparison == 0) {
      comparison = Long.compare(sortedPrimaryKeys[left], sortedPrimaryKeys[right]);
    }
    return command.isDescendingOrder() ? -comparison : comparison;
  }

  private void swapSortedRows(int left, int right) {
    long key = sortedKeys[left];
    sortedKeys[left] = sortedKeys[right];
    sortedKeys[right] = key;
    long primaryKey = sortedPrimaryKeys[left];
    sortedPrimaryKeys[left] = sortedPrimaryKeys[right];
    sortedPrimaryKeys[right] = primaryKey;
    long nullMask = sortedNullMasks[left];
    sortedNullMasks[left] = sortedNullMasks[right];
    sortedNullMasks[right] = nullMask;
    boolean keyNull = sortedKeyNulls[left];
    sortedKeyNulls[left] = sortedKeyNulls[right];
    sortedKeyNulls[right] = keyNull;
    int leftStart = left * TableSchema.MAXIMUM_COLUMNS;
    int rightStart = right * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < projectedColumnCount; index++) {
      long value = sortedValues[leftStart + index];
      sortedValues[leftStart + index] = sortedValues[rightStart + index];
      sortedValues[rightStart + index] = value;
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
        int column = updatedColumns[index];
        boolean nullValue = command.updateIsNull(index);
        long updatedValue = command.updateIsDefault(index)
            ? table.defaultValue(column) : command.updateValue(index);
        if (command.isRelativeUpdate(index)) {
          int sourceColumn = updateSourceColumns[index];
          nullValue = isNull(fetched, table, sourceColumn);
          if (nullValue && !table.isNullable(column)) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
          if (!nullValue) {
            long sourceValue = readColumn(primaryKey, fetched, sourceColumn);
            boolean subtract = command.isSubtractUpdate(index);
            updatedValue = subtract
                ? sourceValue - updatedValue : sourceValue + updatedValue;
            if (arithmeticOverflow(
                sourceValue,
                command.updateValue(index),
                updatedValue,
                subtract)) {
              return StatusCode.RESOURCE_EXHAUSTED;
            }
          }
        }
        row.putLong((column - 1) * Long.BYTES, updatedValue);
        long nullMask = row.getLong(table.nullMaskOffset());
        row.putLong(
            table.nullMaskOffset(),
            nullValue
                ? nullMask | 1L << column : nullMask & ~(1L << column));
      }
      status = table.checksSatisfied(primaryKey, row)
          ? session.updateRow(table, primaryKey, row)
          : StatusCode.CHECK_VIOLATION;
    }
    return status;
  }

  private static boolean arithmeticOverflow(
      long left,
      long right,
      long result,
      boolean subtract) {
    return subtract
        ? ((left ^ right) & (left ^ result)) < 0
        : ((left ^ result) & (right ^ result)) < 0;
  }

  private static int checkComparisonCode(SqlComparison comparison) {
    return switch (comparison) {
      case EQUAL -> TableSchema.CHECK_EQUAL;
      case NOT_EQUAL -> TableSchema.CHECK_NOT_EQUAL;
      case LESS_THAN -> TableSchema.CHECK_LESS_THAN;
      case LESS_OR_EQUAL -> TableSchema.CHECK_LESS_OR_EQUAL;
      case GREATER_THAN -> TableSchema.CHECK_GREATER_THAN;
      case GREATER_OR_EQUAL -> TableSchema.CHECK_GREATER_OR_EQUAL;
      case HALF_OPEN_RANGE, IN, NOT_IN -> 0;
    };
  }

  private StatusCode collectMatchedKeys() {
    matchedRowCount = 0;
    boolean bounded = accessPredicate >= 0;
    boolean equality = bounded && accessEquality();
    boolean indexed = bounded
        && predicateColumn > 0
        && table.hasIndexOn(predicateColumn);
    boolean primaryRange = bounded && predicateColumn == 0;
    if ((indexed || primaryRange)
        && equality
        && accessValue() == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long lower = bounded
        ? equality ? accessValue() : accessLowerInclusive() : 0;
    long upper = bounded
        ? equality ? accessValue() + 1 : accessUpperExclusive() : 0;
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
