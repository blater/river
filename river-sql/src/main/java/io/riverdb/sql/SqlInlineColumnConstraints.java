package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses and records single-column key/reference constraints. */
final class SqlInlineColumnConstraints {
  private final SqlParserInput input;

  SqlInlineColumnConstraints(SqlParserInput parserInput) {
    input = parserInput;
  }

  StatusCode primary(SqlCommand command) {
    command.markLastColumnNotNull();
    return single(command, SqlTableConstraintSet.PRIMARY, null, null);
  }

  StatusCode unique(SqlCommand command) {
    StatusCode status = command.markLastColumnUnique();
    return status.isOk()
        ? single(command, SqlTableConstraintSet.UNIQUE, null, null) : status;
  }

  StatusCode reference(CharSequence sql, SqlCommand command) {
    StatusCode status = parseReference(sql, command);
    int column = command.columnCount() - 1;
    return status.isOk() ? single(
        command,
        SqlTableConstraintSet.FOREIGN,
        command.columnReferenceTableName(column),
        command.columnReferenceColumnName(column)) : status;
  }

  private StatusCode single(
      SqlCommand command, int kind, CharSequence table, CharSequence target) {
    long checkpoint = command.tableConstraints.checkpoint();
    StatusCode status = command.beginTableConstraint(kind);
    if (status.isOk() && table != null) {
      command.writableTableConstraintReferenceTable().copyFrom(table);
    }
    status = status.isOk()
        ? command.addTableConstraintPart(
            command.columnName(command.columnCount() - 1), target) : status;
    if (!status.isOk()) command.tableConstraints.rollback(checkpoint);
    return status;
  }

  private StatusCode parseReference(CharSequence sql, SqlCommand command) {
    int last = command.columnCount() - 1;
    if (command.columnHasReference(last)) return StatusCode.INVALID_EXTERNAL_INPUT;
    SqlIdentifier table = command.writableLastColumnReferenceTableName();
    SqlIdentifier column = command.writableLastColumnReferenceColumnName();
    if (table == null || column == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = input.identifier(sql, table);
    if (status.isOk()) status = input.requireCharacter(sql, '(');
    if (status.isOk()) status = input.identifier(sql, column);
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    return status.isOk() ? command.markLastColumnReference() : status;
  }
}
