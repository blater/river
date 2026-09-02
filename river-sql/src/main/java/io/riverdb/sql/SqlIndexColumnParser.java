package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Parses the bounded, duplicate-free key-part list of a CREATE INDEX command. */
final class SqlIndexColumnParser {
  private SqlIndexColumnParser() { }

  static StatusCode parse(SqlParserInput input, CharSequence sql, SqlCommand command) {
    int part = 0;
    while (part < SqlShapeLimits.MAX_KEY_PARTS) {
      SqlIdentifier column = command.writableNextColumnName();
      if (column == null) return StatusCode.RESOURCE_EXHAUSTED;
      StatusCode status = input.identifier(sql, column);
      if (!status.isOk()) return status;
      if (duplicate(command, part)) return StatusCode.INVALID_EXTERNAL_INPUT;
      part++;
      if (!input.consumeCharacter(sql, ',')) {
        return input.requireCharacter(sql, ')');
      }
    }
    return input.consumeCharacter(sql, ')')
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private static boolean duplicate(SqlCommand command, int current) {
    CharSequence candidate = command.columnName(current);
    for (int prior = 0; prior < current; prior++) {
      if (same(candidate, command.columnName(prior))) return true;
    }
    return false;
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) return false;
    }
    return true;
  }
}
