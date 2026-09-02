package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;

/** Selects one enforcing table lifecycle and rejects unsupported legacy constraint shapes. */
final class SqlCreateTableLifecycleAdmission {
  private SqlCreateTableLifecycleAdmission() {
  }

  static StatusCode validate(SqlCommand command) {
    if (!requiresLegacy(command)) return StatusCode.OK;
    for (int column = 0; column < command.columnCount(); column++) {
      if (SqlTypeDescriptor.isWideDecimal(command.columnTypeDescriptor(column))
          && (command.columnHasDefault(column) || command.columnHasCheck(column))) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
    }
    int primaryCount = 0;
    for (int constraint = 0; constraint < command.tableConstraintCount(); constraint++) {
      int kind = command.tableConstraintKind(constraint);
      if (kind == SqlCommand.CONSTRAINT_CHECK) continue;
      if (kind == SqlCommand.CONSTRAINT_PRIMARY_KEY) primaryCount++;
      if (!legacyConstraintEnforced(command, constraint, kind)) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
    }
    return primaryCount == 1 ? StatusCode.OK : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  static boolean requiresLegacy(SqlCommand command) {
    if (command.hasPrimaryKeyIdentity()) return true;
    boolean constrained = false;
    for (int column = 0; column < command.columnCount(); column++) {
      constrained |= command.columnHasDefault(column) || command.columnHasCheck(column);
    }
    for (int constraint = 0; constraint < command.tableConstraintCount(); constraint++) {
      constrained |= command.tableConstraintKind(constraint) == SqlCommand.CONSTRAINT_CHECK;
    }
    return constrained && !SqlDescriptorLifecycleAdmission.constraintShapesReady(command);
  }

  private static boolean legacyConstraintEnforced(
      SqlCommand command, int constraint, int kind) {
    if (command.tableConstraintPartCount(constraint) != 1) return false;
    int column = findColumn(
        command, command.tableConstraintPartName(constraint, 0));
    if (column < 0) return false;
    if (kind == SqlCommand.CONSTRAINT_PRIMARY_KEY) {
      return column == 0 && command.columnTypeDescriptor(0) == SqlTypeDescriptor.BIGINT;
    }
    if (kind == SqlCommand.CONSTRAINT_UNIQUE) {
      return column == 0 || command.columnIsUnique(column);
    }
    return kind == SqlCommand.CONSTRAINT_FOREIGN_KEY
        && column > 0 && command.columnHasReference(column);
  }

  private static int findColumn(SqlCommand command, CharSequence name) {
    for (int column = 0; column < command.columnCount(); column++) {
      if (SqlDescriptorPrimaryPredicate.same(name, command.columnName(column))) return column;
    }
    return -1;
  }
}
