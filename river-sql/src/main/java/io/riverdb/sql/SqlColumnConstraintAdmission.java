package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Independently bounds secondary and foreign-key column constraints. */
final class SqlColumnConstraintAdmission {
  private SqlColumnConstraintAdmission() {
  }

  static StatusCode unique(SqlCommand command) {
    if (command.columnCount < 1 || command.columnUnique[command.columnCount - 1]) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (count(command.columnUnique, command.columnCount)
        >= SqlShapeLimits.MAX_SECONDARY_INDEXES) return StatusCode.RESOURCE_EXHAUSTED;
    command.columnUnique[command.columnCount - 1] = true;
    return StatusCode.OK;
  }

  static StatusCode reference(SqlCommand command) {
    int column = command.columnCount - 1;
    if (column < 0 || command.columnReferences[column]
        || command.columnReferenceTableNames[column].length() == 0
        || command.columnReferenceColumnNames[column].length() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (count(command.columnReferences, command.columnCount)
        >= SqlShapeLimits.MAX_FOREIGN_KEYS) return StatusCode.RESOURCE_EXHAUSTED;
    command.columnReferences[column] = true;
    return StatusCode.OK;
  }

  private static int count(boolean[] constraints, int columns) {
    int count = 0;
    for (int index = 0; index < columns; index++) if (constraints[index]) count++;
    return count;
  }
}
