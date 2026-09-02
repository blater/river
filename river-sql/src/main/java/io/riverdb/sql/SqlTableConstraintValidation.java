package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Resolves CREATE TABLE constraint names before any catalog reservation. */
final class SqlTableConstraintValidation {
  private SqlTableConstraintValidation() { }

  static StatusCode validate(SqlCommand command) {
    if (command.columnCount() < 1 || SqlTableConstraintNames.duplicateColumns(command)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int constraint = 0; constraint < command.tableConstraintCount(); constraint++) {
      StatusCode status = validateConstraint(command, constraint);
      if (!status.isOk()) return status;
    }
    return SqlTableConstraintNames.validIdentity(command)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static StatusCode validateConstraint(SqlCommand command, int constraint) {
    if (SqlTableConstraintNames.duplicateName(command, constraint)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int kind = command.tableConstraintKind(constraint);
    StatusCode status = validatePartCount(kind, command.tableConstraintPartCount(constraint));
    if (!status.isOk()) return status;
    status = resolveParts(command, constraint, kind);
    if (!status.isOk()) return status;
    if (kind == SqlTableConstraintSet.FOREIGN && !validForeign(command, constraint)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return kind == SqlTableConstraintSet.UNIQUE
        && SqlTableConstraintNames.duplicateParts(command, constraint)
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }

  private static StatusCode validatePartCount(int kind, int parts) {
    if (kind == SqlTableConstraintSet.CHECK || parts > 0) {
      return parts > SqlShapeLimits.MAX_KEY_PARTS && kind != SqlTableConstraintSet.CHECK
          ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static StatusCode resolveParts(SqlCommand command, int constraint, int kind) {
    int parts = command.tableConstraintPartCount(constraint);
    for (int part = 0; part < parts; part++) {
      int column = SqlTableConstraintNames.find(
          command, command.tableConstraintPartName(constraint, part));
      if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (kind == SqlTableConstraintSet.PRIMARY) command.markColumnNotNull(column);
    }
    return StatusCode.OK;
  }

  static int find(SqlCommand command, CharSequence name) {
    return SqlTableConstraintNames.find(command, name);
  }

  private static boolean validForeign(SqlCommand command, int constraint) {
    if (command.tableConstraintReferenceTableName(constraint).length() == 0) return false;
    int parts = command.tableConstraintPartCount(constraint);
    for (int part = 0; part < parts; part++) {
      CharSequence target = command.tableConstraintReferencePartName(constraint, part);
      if (target == null || target.length() == 0) return false;
    }
    return true;
  }

}
