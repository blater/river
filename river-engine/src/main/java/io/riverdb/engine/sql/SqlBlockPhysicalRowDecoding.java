package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;
import java.util.Arrays;

/** Copies one River-owned physical row into synchronous block evaluator scratch. */
final class SqlBlockPhysicalRowDecoding {
  private boolean[] textRequired = new boolean[0];
  private int preparedColumns;
  SqlBlockPhysicalRowDecoding() { }

  SqlBlockPhysicalRowDecoding(SqlRetainedArrayAllocator retainedAllocator) { }

  StatusCode prepare(TableDefinition table, SqlBlockRow destination) {
    return prepare(table, destination, null);
  }

  StatusCode prepare(
      TableDefinition table, SqlBlockRow destination, SqlBoundBlockPlans plans) {
    if (table == null || destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = destination.reset(table.columnCount());
    if (!status.isOk()) return status;
    int columns = table.columnCount();
    if (textRequired.length < columns) {
      try {
        textRequired = new boolean[columns];
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    Arrays.fill(textRequired, 0, columns, true);
    if (plans != null) {
      for (int column = 0; column < columns; column++) {
        if (table.isVarchar(column)) {
          textRequired[column] = plans.physicalColumnLive(column);
        }
      }
    }
    preparedColumns = columns;
    return StatusCode.OK;
  }

  StatusCode read(
      long primaryKey,
      HeapRowResult source,
      TableDefinition table,
      SqlBlockRow destination) {
    if (source == null || table == null
        || source.length() < table.fixedRowBytes()
        || source.length() > table.maximumRowBytes()) return StatusCode.CORRUPTION;
    StatusCode admitted = destination.reset(table.columnCount());
    if (!admitted.isOk()) return admitted;
    destination.setKey(primaryKey);
    destination.setValue(0, primaryKey);
    for (int column = 1; column < table.columnCount(); column++) {
      if (SqlPhysicalRowNulls.get(source, table, column)) {
        destination.setNull(column);
        continue;
      }
      if (table.isVarchar(column) && column < preparedColumns
          && !textRequired[column]) {
        destination.setValue(column, 0);
        destination.setTextLength(column, 0);
        continue;
      }
      long value = source.getLong(table.valueOffset(column));
      if (!table.isVarchar(column)) {
        int descriptor = table.typeDescriptor(column);
        if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
          long high = source.getLong(table.highValueOffset(column));
          destination.setDecimal128(column, high, value);
          continue;
        }
        destination.setValue(column, value);
        continue;
      }
      int offset = (int) (value >>> 32);
      int length = (int) value;
      if (offset < table.fixedRowBytes()
          || length < 0
          || offset > source.length() - length) return StatusCode.CORRUPTION;
      if (length == 0) {
        destination.setValue(column, 0);
        destination.setTextLength(column, 0);
        continue;
      }
      StatusCode prepared = destination.prepareText(column, length);
      if (!prepared.isOk()) return prepared;
      int characters = Utf8RowText.decode(
          source, offset, length, destination.text(column));
      if (characters < 0) return StatusCode.CORRUPTION;
      destination.setValue(column, 0);
      destination.setTextLength(column, characters);
    }
    return StatusCode.OK;
  }

  void reset() { preparedColumns = 0; }
}
