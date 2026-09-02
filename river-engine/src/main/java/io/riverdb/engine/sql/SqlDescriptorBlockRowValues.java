package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;

/** Reusable conversion from descriptor storage values to one expression block row. */
final class SqlDescriptorBlockRowValues {
  private final SqlBlockRow row;
  private final SqlDescriptorPredicateColumns predicateColumns;
  private TableDescriptor table;
  private boolean predicateActive;

  SqlDescriptorBlockRowValues() {
    row = new SqlBlockRow();
    predicateColumns = new SqlDescriptorPredicateColumns(null);
  }

  SqlDescriptorBlockRowValues(SqlSessionShapeBudget budget) {
    row = new SqlBlockRow(budget);
    predicateColumns = new SqlDescriptorPredicateColumns(budget);
  }

  StatusCode prepare(TableDescriptor descriptor) {
    return prepare(descriptor, false);
  }

  StatusCode prepare(
      TableDescriptor descriptor, SqlBoundBooleanPredicateProgram boundPredicate) {
    table = descriptor;
    predicateActive = true;
    StatusCode status = predicateColumns.prepare(boundPredicate, descriptor.columnCount());
    if (status.isOk()) status = row.reset(descriptor.columnCount());
    return status;
  }

  StatusCode prepare(TableDescriptor descriptor, boolean retainLogicalRowId) {
    table = descriptor;
    predicateActive = false;
    predicateColumns.reset();
    int columns = descriptor.columnCount() + (retainLogicalRowId ? 1 : 0);
    return row.reset(columns);
  }

  StatusCode load(SqlValueBuffer values) {
    return load(values, 0, false);
  }

  StatusCode load(SqlValueBuffer values, long logicalRowId) {
    return load(values, logicalRowId, true);
  }

  private StatusCode load(
      SqlValueBuffer values, long logicalRowId, boolean retainLogicalRowId) {
    if (table == null) return StatusCode.CONFLICT;
    int columns = table.columnCount() + (retainLogicalRowId ? 1 : 0);
    StatusCode status = predicateActive ? StatusCode.OK : row.reset(columns);
    if (!predicateActive) {
      for (int column = 0; status.isOk() && column < table.columnCount(); column++) {
        status = copy(values, column);
      }
    } else {
      for (int index = 0; status.isOk() && index < predicateColumns.count(); index++) {
        int column = predicateColumns.column(index);
        row.clearValue(column);
        status = copy(values, column);
      }
    }
    if (status.isOk() && retainLogicalRowId) {
      row.setValue(table.columnCount(), logicalRowId);
    }
    return status;
  }

  SqlBlockRow row() { return row; }

  void reset() {
    table = null;
    predicateActive = false;
    predicateColumns.reset();
  }

  private StatusCode copy(SqlValueBuffer values, int column) {
    if (values.isNull(column)) {
      row.setNull(column);
      return StatusCode.OK;
    }
    int descriptor = table.typeDescriptorAt(column);
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      row.setDecimal128(
          column, values.highValueAt(column), values.valueAt(column));
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      row.setValue(column, values.valueAt(column));
      return StatusCode.OK;
    }
    int bytes = values.textByteLengthAt(column);
    if (bytes < 0) return StatusCode.CORRUPTION;
    if (bytes == 0) {
      row.setTextLength(column, 0);
      return StatusCode.OK;
    }
    StatusCode status = row.prepareText(column, bytes);
    if (!status.isOk()) return status;
    int length = values.copyTextChars(column, row.text(column), 0);
    if (length < 0) return StatusCode.CORRUPTION;
    row.setTextLength(column, length);
    return StatusCode.OK;
  }
}
