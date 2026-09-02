package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Reusable source-to-descriptor-column mapping for named mutations. */
final class SqlDescriptorColumnMapping {
  private static final int INITIAL_COLUMNS = 8;
  private int[] sources = new int[0];

  StatusCode mapInsert(SqlCommand command, TableDescriptor table) {
    StatusCode status = reserve(table.columnCount());
    if (!status.isOk()) return status;
    clear(table.columnCount());
    if (command.columnCount() == 0) return positionalInsert(command, table);
    if (command.columnCount() != command.insertColumnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int source = 0; source < command.columnCount(); source++) {
      int column = table.findColumn(command.columnName(source));
      if (column < 0 || sources[column] >= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      sources[column] = source;
    }
    return StatusCode.OK;
  }

  StatusCode mapUpdate(SqlCommand command, TableDescriptor table) {
    StatusCode status = reserve(table.columnCount());
    if (!status.isOk()) return status;
    clear(table.columnCount());
    if (command.updateColumnCount() <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int source = 0; source < command.updateColumnCount(); source++) {
      int column = table.findColumn(command.columnName(source));
      if (column < 0 || sources[column] >= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      sources[column] = source;
    }
    return StatusCode.OK;
  }

  int sourceAt(int column) { return sources[column]; }

  private StatusCode positionalInsert(SqlCommand command, TableDescriptor table) {
    if (command.insertColumnCount() != table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < table.columnCount(); index++) sources[index] = index;
    return StatusCode.OK;
  }

  private StatusCode reserve(int requested) {
    if (requested <= sources.length) return StatusCode.OK;
    try {
      sources = new int[BoundedArrayGrowth.capacity(
          sources.length, requested, SqlShapeLimits.MAX_TABLE_COLUMNS, INITIAL_COLUMNS)];
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private void clear(int count) {
    for (int index = 0; index < count; index++) sources[index] = -1;
  }
}
