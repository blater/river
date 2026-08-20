package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.SequenceValueResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.storage.heap.HeapRowResult;

/** Coordinates bounded INSERT, UPDATE, and DELETE execution. */
final class SqlDmlExecutor {
  private final RelationalDatabase database;
  private final RelationalSession session;
  private final SqlMutationRowEncoder rows;
  private final SqlMutationKeyCollector matches;
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final SequenceValueResult sequenceValue = new SequenceValueResult();
  private final long[] generatedInsertKeys =
      new long[SqlCommand.MAXIMUM_INSERT_ROWS];

  private int matchedRowCount;
  private long directKey;

  SqlDmlExecutor(
      RelationalDatabase relationalDatabase,
      RelationalSession relationalSession,
      SqlTemporalContext temporal,
      SqlRowProjectionEvaluator rowExpressions,
      SqlBoundPredicateEvaluator predicates) {
    database = relationalDatabase;
    session = relationalSession;
    rows = new SqlMutationRowEncoder(temporal, rowExpressions);
    matches = new SqlMutationKeyCollector(relationalSession, predicates);
  }

  boolean handles(SqlCommandType type) {
    return type == SqlCommandType.INSERT
        || type == SqlCommandType.UPDATE
        || type == SqlCommandType.DELETE;
  }

  StatusCode execute(
      SqlCommand command,
      BoundSqlStatement bound,
      SqlExecutionResult result) {
    matchedRowCount = 0;
    return switch (command.type()) {
      case INSERT -> executeInsert(command, bound, result);
      case UPDATE -> executeUpdate(command, bound);
      case DELETE -> executeDelete(command, bound);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
  }

  int affectedRows(SqlCommand command) {
    return command.type() == SqlCommandType.INSERT
        ? command.insertRowCount() : matchedRowCount;
  }

  boolean hasOpenResources() {
    return matches.hasOpenResources();
  }

  StatusCode closeResources() {
    return matches.close();
  }

  private StatusCode executeInsert(
      SqlCommand command,
      BoundSqlStatement bound,
      SqlExecutionResult result) {
    StatusCode status = bound.table.hasIdentity()
        ? allocateIdentityKeys(command, bound.table) : StatusCode.OK;
    for (int index = 0;
        status.isOk() && index < command.insertRowCount();
        index++) {
      status = insertRow(command, bound, index);
    }
    if (status.isOk() && bound.table.hasIdentity()) {
      result.setGeneratedKey(generatedInsertKeys[0]);
    }
    return status;
  }

  private StatusCode insertRow(
      SqlCommand command, BoundSqlStatement bound, int index) {
    StatusCode status = bound.table.hasIdentity()
        ? StatusCode.OK : rows.resolveInsertKey(command, bound, index);
    if (status.isOk()) {
      status = rows.encodeInsert(command, bound, index);
    }
    if (!status.isOk()) {
      return status;
    }
    long key = bound.table.hasIdentity()
        ? generatedInsertKeys[index] : rows.insertKey();
    return session.insertRow(bound.table, key, rows.insertRow());
  }

  private StatusCode executeUpdate(
      SqlCommand command, BoundSqlStatement bound) {
    if (requiresScan(command, bound)) {
      StatusCode status = collectMatches(command, bound);
      for (int index = 0; status.isOk() && index < matchedRowCount; index++) {
        status = updatePrimaryKey(command, bound, matches.key(index));
      }
      return status;
    }
    StatusCode status = resolveDirectKey(command, bound);
    if (status.isOk()) {
      status = updatePrimaryKey(command, bound, directKey);
      matchedRowCount = status.isOk() ? 1 : 0;
    }
    return status;
  }

  private StatusCode executeDelete(
      SqlCommand command, BoundSqlStatement bound) {
    if (requiresScan(command, bound)) {
      StatusCode status = collectMatches(command, bound);
      for (int index = 0; status.isOk() && index < matchedRowCount; index++) {
        status = session.deleteLong(bound.table, matches.key(index));
      }
      return status;
    }
    StatusCode status = resolveDirectKey(command, bound);
    if (status.isOk()) {
      status = session.deleteLong(bound.table, directKey);
      matchedRowCount = status.isOk() ? 1 : 0;
    }
    return status;
  }

  private StatusCode collectMatches(
      SqlCommand command, BoundSqlStatement bound) {
    StatusCode status = matches.collect(command, bound);
    matchedRowCount = matches.count();
    return status;
  }

  private StatusCode resolveDirectKey(
      SqlCommand command, BoundSqlStatement bound) {
    directKey = bound.accessValue;
    if (bound.predicateColumn == 0) {
      return StatusCode.OK;
    }
    StatusCode status = session.fetchByUniqueValue(
        bound.table, bound.predicateColumn, directKey, indexed);
    if (status.isOk()) {
      directKey = indexed.key();
    }
    return status;
  }

  private StatusCode allocateIdentityKeys(
      SqlCommand command, TableDefinition table) {
    for (int index = 0; index < command.insertRowCount(); index++) {
      StatusCode status = database.nextIdentityValue(table, sequenceValue);
      if (!status.isOk()) {
        return status;
      }
      generatedInsertKeys[index] = sequenceValue.value();
    }
    return StatusCode.OK;
  }

  private StatusCode updatePrimaryKey(
      SqlCommand command, BoundSqlStatement bound, long primaryKey) {
    StatusCode status = session.fetch(bound.table, primaryKey, fetched);
    if (status.isOk()) {
      status = rows.encodeUpdate(command, bound, fetched, primaryKey);
    }
    if (status.isOk()) {
      status = session.updateRow(bound.table, primaryKey, rows.updatedRow());
    }
    return status;
  }

  private static boolean requiresScan(
      SqlCommand command, BoundSqlStatement bound) {
    return bound.predicateCount != 1
        || bound.accessComparison != SqlComparison.EQUAL
        || bound.predicateColumn > 0
            && !bound.table.hasUniqueIndexOn(bound.predicateColumn);
  }

}
