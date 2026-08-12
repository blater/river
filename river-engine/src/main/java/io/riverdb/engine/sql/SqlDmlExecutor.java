package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.SequenceValueResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns reusable row encoding and validation state for INSERT and UPDATE. */
final class SqlDmlExecutor {
  private final RelationalDatabase database;
  private final RelationalSession session;
  private final SqlExpressionEvaluator expressions;
  private final ByteBuffer insertRow = ByteBuffer.allocateDirect(
      TableSchema.MAXIMUM_ROW_BYTES);
  private final ByteBuffer sourceRow = ByteBuffer.allocateDirect(
      TableSchema.MAXIMUM_ROW_BYTES);
  private final ByteBuffer updatedRow = ByteBuffer.allocateDirect(
      TableSchema.MAXIMUM_ROW_BYTES);
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final RelationalScanCursor scanCursor = new RelationalScanCursor();
  private final RelationalScanResult scanRow = new RelationalScanResult();
  private final SequenceValueResult sequenceValue = new SequenceValueResult();
  private final long[] matchedKeys = new long[SqlCommand.MAXIMUM_INSERT_ROWS];
  private final long[] generatedInsertKeys =
      new long[SqlCommand.MAXIMUM_INSERT_ROWS];
  private int matchedRowCount;

  SqlDmlExecutor(
      RelationalDatabase relationalDatabase,
      RelationalSession relationalSession,
      SqlExpressionEvaluator evaluator) {
    database = relationalDatabase;
    session = relationalSession;
    expressions = evaluator;
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
    if (command.type() == SqlCommandType.INSERT) {
      StatusCode status = bound.table.hasIdentity()
          ? allocateIdentityKeys(command, bound.table) : StatusCode.OK;
      for (int index = 0;
          status.isOk() && index < command.insertRowCount();
          index++) {
        status = encodeInsertRow(command, bound, index);
        long key = bound.table.hasIdentity()
            ? generatedInsertKeys[index]
            : command.insertValue(index, bound.insertSourceByColumn[0]);
        status = status.isOk() && bound.table.checksSatisfied(key, insertRow)
            ? session.insertRow(bound.table, key, insertRow)
            : status.isOk() ? StatusCode.CHECK_VIOLATION : status;
      }
      if (status.isOk() && bound.table.hasIdentity()) {
        result.setGeneratedKey(generatedInsertKeys[0]);
      }
      return status;
    }
    if (command.type() == SqlCommandType.UPDATE) {
      return executeUpdate(command, bound);
    }
    if (command.type() == SqlCommandType.DELETE) {
      return executeDelete(command, bound);
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  int affectedRows(SqlCommand command) {
    return command.type() == SqlCommandType.INSERT
        ? command.insertRowCount() : matchedRowCount;
  }

  private StatusCode executeUpdate(
      SqlCommand command, BoundSqlStatement bound) {
    if (bound.predicateCount != 1
        || !accessEquality(command, bound)
        || bound.predicateColumn > 0
            && !bound.table.hasUniqueIndexOn(bound.predicateColumn)) {
      StatusCode status = collectMatchedKeys(command, bound);
      for (int index = 0; status.isOk() && index < matchedRowCount; index++) {
        status = updatePrimaryKey(command, bound, matchedKeys[index]);
      }
      return status;
    }
    long primaryKey = accessValue(command, bound);
    StatusCode status = StatusCode.OK;
    if (bound.predicateColumn > 0) {
      status = session.fetchByUniqueValue(
          bound.table, bound.predicateColumn, accessValue(command, bound), indexed);
      primaryKey = indexed.key();
    }
    if (status.isOk()) {
      status = updatePrimaryKey(command, bound, primaryKey);
      matchedRowCount = status.isOk() ? 1 : 0;
    }
    return status;
  }

  private StatusCode executeDelete(
      SqlCommand command, BoundSqlStatement bound) {
    if (bound.predicateCount != 1
        || !accessEquality(command, bound)
        || bound.predicateColumn > 0
            && !bound.table.hasUniqueIndexOn(bound.predicateColumn)) {
      StatusCode status = collectMatchedKeys(command, bound);
      for (int index = 0; status.isOk() && index < matchedRowCount; index++) {
        status = session.deleteLong(bound.table, matchedKeys[index]);
      }
      return status;
    }
    long primaryKey = accessValue(command, bound);
    StatusCode status = StatusCode.OK;
    if (bound.predicateColumn > 0) {
      status = session.fetchByUniqueValue(
          bound.table, bound.predicateColumn, accessValue(command, bound), indexed);
      primaryKey = indexed.key();
    }
    if (status.isOk()) {
      status = session.deleteLong(bound.table, primaryKey);
      matchedRowCount = status.isOk() ? 1 : 0;
    }
    return status;
  }

  private StatusCode allocateIdentityKeys(
      SqlCommand command, TableDefinition table) {
    StatusCode status = StatusCode.OK;
    for (int index = 0;
        status.isOk() && index < command.insertRowCount();
        index++) {
      status = database.nextIdentityValue(table, sequenceValue);
      if (status.isOk()) {
        generatedInsertKeys[index] = sequenceValue.value();
      }
    }
    return status;
  }

  private StatusCode updatePrimaryKey(
      SqlCommand command, BoundSqlStatement bound, long primaryKey) {
    StatusCode status = session.fetch(bound.table, primaryKey, fetched);
    if (status.isOk()) {
      status = encodeUpdatedRow(command, bound, fetched, primaryKey);
    }
    if (status.isOk()) {
      status = bound.table.checksSatisfied(primaryKey, updatedRow)
          ? session.updateRow(bound.table, primaryKey, updatedRow)
          : StatusCode.CHECK_VIOLATION;
    }
    return status;
  }

  private StatusCode collectMatchedKeys(
      SqlCommand command, BoundSqlStatement bound) {
    StatusCode cleanup = closeResources();
    if (!cleanup.isOk()) {
      return cleanup;
    }
    matchedRowCount = 0;
    boolean bounded = bound.accessPredicate >= 0;
    boolean equality = bounded && accessEquality(command, bound);
    boolean indexed = bounded
        && bound.predicateColumn > 0
        && bound.table.hasIndexOn(bound.predicateColumn);
    boolean primaryRange = bounded && bound.predicateColumn == 0;
    if ((indexed || primaryRange)
        && equality
        && accessValue(command, bound) == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long lower = bounded
        ? equality
            ? accessValue(command, bound)
            : accessLowerInclusive(command, bound)
        : 0;
    long upper = bounded
        ? equality
            ? accessValue(command, bound) + 1
            : accessUpperExclusive(command, bound)
        : 0;
    StatusCode status = indexed
        ? session.beginValueScan(
            bound.table,
            bound.predicateColumn,
            lower,
            upper,
            scanCursor)
        : primaryRange
            ? session.beginScan(bound.table, lower, upper, scanCursor)
            : session.beginScan(bound.table, scanCursor);
    boolean active = status.isOk();
    while (status.isOk()) {
      status = indexed
          ? session.nextValueScan(bound.table, scanCursor, scanRow, this.indexed)
          : session.nextScan(scanCursor, scanRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      HeapRowResult source = indexed ? this.indexed.row() : scanRow.row();
      long primaryKey = indexed ? this.indexed.key() : scanRow.key();
      if (status.isOk()) {
        status = validateRow(source, bound.table);
      }
      if (status.isOk() && !matchesPredicates(
          command, bound, primaryKey, source)) {
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
      StatusCode close = session.closeScan(scanCursor);
      if (status.isOk()) {
        status = close;
      }
      if (close.isOk()) {
        scanCursor.reset();
      }
    }
    return status;
  }

  boolean hasOpenResources() {
    return scanCursor.isActive();
  }

  StatusCode closeResources() {
    if (!scanCursor.isActive()) {
      return StatusCode.OK;
    }
    StatusCode status = session.closeScan(scanCursor);
    if (status.isOk()) {
      status = scanCursor.reset();
    }
    return status;
  }

  private boolean matchesPredicates(
      SqlCommand command,
      BoundSqlStatement bound,
      long primaryKey,
      HeapRowResult source) {
    boolean conjunction = true;
    for (int index = 0; index < bound.predicateCount; index++) {
      if (command.predicateStartsDisjunction(index)) {
        if (conjunction) {
          return true;
        }
        conjunction = true;
      }
      if (!conjunction) {
        continue;
      }
      int column = bound.predicateColumns[index];
      long value = expressions.readColumn(primaryKey, source, column);
      boolean nullValue = expressions.isNull(source, bound.table, column);
      if (command.isNullPredicate(index)) {
        if (nullValue == command.isNullPredicateNegated(index)) {
          conjunction = false;
        }
      } else if (nullValue
          || (bound.table.isVarchar(column)
              ? !expressions.matchesTextComparison(
                  source, bound.table, column, command, index)
              : !expressions.matchesComparison(value, command, index))) {
        conjunction = false;
      }
    }
    return conjunction;
  }

  private boolean accessEquality(
      SqlCommand command, BoundSqlStatement bound) {
    return bound.accessPredicate >= 0
        && command.isEqualityPredicate(bound.accessPredicate);
  }

  private long accessValue(
      SqlCommand command, BoundSqlStatement bound) {
    return command.predicateValue(bound.accessPredicate);
  }

  private long accessLowerInclusive(
      SqlCommand command, BoundSqlStatement bound) {
    return command.predicateLowerInclusive(bound.accessPredicate);
  }

  private long accessUpperExclusive(
      SqlCommand command, BoundSqlStatement bound) {
    return command.predicateUpperExclusive(bound.accessPredicate);
  }

  ByteBuffer insertRow() {
    return insertRow;
  }

  ByteBuffer updatedRow() {
    return updatedRow;
  }

  StatusCode encodeInsertRow(
      SqlCommand command, BoundSqlStatement bound, int rowIndex) {
    TableDefinition table = bound.table;
    insertRow.clear();
    int payloadOffset = table.fixedRowBytes();
    long nullMask = 0;
    for (int column = 1; column < table.columnCount(); column++) {
      int source = bound.insertSourceByColumn[column];
      boolean omitted = source < 0;
      boolean explicitDefault = !omitted
          && command.insertIsDefault(rowIndex, source);
      boolean nullValue = omitted
          ? !table.hasDefault(column) : command.insertIsNull(rowIndex, source);
      if (nullValue) {
        nullMask |= 1L << column;
      }
      int slotOffset = (column - 1) * Long.BYTES;
      boolean useDefault = omitted && table.hasDefault(column) || explicitDefault;
      if (table.isVarchar(column)) {
        if (nullValue) {
          insertRow.putLong(slotOffset, 0);
          continue;
        }
        insertRow.position(payloadOffset);
        int bytes = useDefault
            ? table.copyDefaultText(column, insertRow)
            : command.copyText(command.insertValue(rowIndex, source), insertRow);
        if (bytes < 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        insertRow.putLong(
            slotOffset,
            (long) payloadOffset << 32 | Integer.toUnsignedLong(bytes));
        payloadOffset += bytes;
      } else {
        insertRow.putLong(
            slotOffset,
            useDefault
                ? table.defaultValue(column)
                : command.insertValue(rowIndex, source));
      }
    }
    insertRow.putLong(table.nullMaskOffset(), nullMask);
    insertRow.position(0);
    insertRow.limit(payloadOffset);
    return table.isValidRow(insertRow)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode encodeUpdatedRow(
      SqlCommand command,
      BoundSqlStatement bound,
      HeapRowResult source,
      long primaryKey) {
    StatusCode status = copySourceRow(source, bound.table);
    if (!status.isOk()) {
      return status;
    }
    TableDefinition table = bound.table;
    updatedRow.clear();
    int payloadOffset = table.fixedRowBytes();
    long nullMask = sourceRow.getLong(table.nullMaskOffset());
    for (int column = 1; column < table.columnCount(); column++) {
      int update = updateIndex(bound, column);
      boolean nullValue = update >= 0
          ? command.updateIsNull(update) : (nullMask & 1L << column) != 0;
      if (update >= 0 && command.isRelativeUpdate(update)) {
        int sourceColumn = bound.updateSourceColumns[update];
        nullValue = expressions.isNull(source, table, sourceColumn);
        if (nullValue && !table.isNullable(column)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      }
      if (nullValue) {
        nullMask |= 1L << column;
      } else {
        nullMask &= ~(1L << column);
      }
      int slotOffset = (column - 1) * Long.BYTES;
      if (table.isVarchar(column)) {
        if (nullValue) {
          updatedRow.putLong(slotOffset, 0);
          continue;
        }
        updatedRow.position(payloadOffset);
        int bytes;
        if (update < 0) {
          long handle = sourceRow.getLong(slotOffset);
          int sourceOffset = (int) (handle >>> 32);
          bytes = (int) handle;
          for (int index = 0; index < bytes; index++) {
            updatedRow.put(sourceRow.get(sourceOffset + index));
          }
        } else if (command.updateIsDefault(update)) {
          bytes = table.copyDefaultText(column, updatedRow);
        } else {
          bytes = command.copyText(command.updateValue(update), updatedRow);
        }
        if (bytes < 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        updatedRow.putLong(
            slotOffset,
            (long) payloadOffset << 32 | Integer.toUnsignedLong(bytes));
        payloadOffset += bytes;
        continue;
      }
      long updatedValue = update < 0
          ? sourceRow.getLong(slotOffset)
          : command.updateIsDefault(update)
              ? table.defaultValue(column) : command.updateValue(update);
      if (update >= 0 && command.isRelativeUpdate(update) && !nullValue) {
        long sourceValue = expressions.readColumn(
            primaryKey, source, bound.updateSourceColumns[update]);
        boolean subtract = command.isSubtractUpdate(update);
        updatedValue = subtract
            ? sourceValue - updatedValue : sourceValue + updatedValue;
        if (expressions.arithmeticOverflow(
            sourceValue,
            command.updateValue(update),
            updatedValue,
            subtract)) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
      }
      updatedRow.putLong(slotOffset, updatedValue);
    }
    updatedRow.putLong(table.nullMaskOffset(), nullMask);
    updatedRow.position(0);
    updatedRow.limit(payloadOffset);
    return table.isValidRow(updatedRow)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode validateRow(HeapRowResult source, TableDefinition definition) {
    if (source.length() < definition.fixedRowBytes()
        || source.length() > definition.maximumRowBytes()) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private StatusCode copySourceRow(
      HeapRowResult source, TableDefinition table) {
    StatusCode status = validateRow(source, table);
    if (!status.isOk()) {
      return status;
    }
    sourceRow.clear();
    sourceRow.limit(source.length());
    status = source.copyTo(sourceRow);
    if (status.isOk()) {
      sourceRow.position(0);
      status = table.isValidRow(sourceRow) ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    return status;
  }

  private int updateIndex(BoundSqlStatement bound, int column) {
    for (int index = 0; index < bound.updatedColumnCount; index++) {
      if (bound.updatedColumns[index] == column) {
        return index;
      }
    }
    return -1;
  }
}
