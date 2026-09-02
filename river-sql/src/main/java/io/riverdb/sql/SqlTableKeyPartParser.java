package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Reusable bounded local/target column lists for one table key constraint. */
final class SqlTableKeyPartParser {
  private final SqlParserInput input;
  private final SqlIdentifier[] parts = parts();
  private final SqlIdentifier target = new SqlIdentifier();

  SqlTableKeyPartParser(SqlParserInput parserInput) {
    input = parserInput;
  }

  int parse(CharSequence sql) {
    if (!input.consumeCharacter(sql, '(')) return -1;
    int count = 0;
    do {
      if (count == parts.length) return -2;
      parts[count].reset();
      if (!input.identifier(sql, parts[count]).isOk()) return -1;
      count++;
    } while (input.consumeCharacter(sql, ','));
    return input.consumeCharacter(sql, ')') ? count : -1;
  }

  StatusCode append(SqlCommand command, int count) {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < count; index++) {
      status = command.addTableConstraintPart(parts[index], null);
    }
    return status;
  }

  int parseTargets(CharSequence sql, SqlCommand command, int locals) {
    if (!input.consumeCharacter(sql, '(')) return -1;
    StatusCode status = StatusCode.OK;
    int count = 0;
    while (status.isOk() && count < locals) {
      target.reset();
      status = input.identifier(sql, target);
      if (status.isOk()) status = command.addTableConstraintPart(parts[count], target);
      count++;
      if (count < locals && !input.consumeCharacter(sql, ',')) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return status.isOk() && input.consumeCharacter(sql, ')') ? count : -1;
  }

  private static SqlIdentifier[] parts() {
    SqlIdentifier[] values = new SqlIdentifier[
        io.riverdb.base.sql.SqlShapeLimits.MAX_KEY_PARTS];
    for (int index = 0; index < values.length; index++) values[index] = new SqlIdentifier();
    return values;
  }
}
