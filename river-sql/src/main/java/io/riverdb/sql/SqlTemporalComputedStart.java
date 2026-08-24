package io.riverdb.sql;

/** Recognition of SQL expressions that begin with a computed temporal value. */
final class SqlTemporalComputedStart {
  private SqlTemporalComputedStart() {
  }

  static boolean consume(SqlParserInput input, CharSequence sql) {
    return input.consumeKeyword(sql, "EXTRACT")
        || input.consumeKeyword(sql, "CAST")
        || input.consumeKeyword(sql, "CURRENT_DATE")
        || input.consumeKeyword(sql, "CURRENT_TIMESTAMP")
        || input.consumeKeyword(sql, "LOCALTIME")
        || input.consumeKeyword(sql, "LOCALTIMESTAMP");
  }

  static boolean isComputed(CharSequence identifier) {
    return same(identifier, "extract")
        || same(identifier, "cast")
        || same(identifier, "current_date")
        || same(identifier, "current_timestamp")
        || same(identifier, "localtime")
        || same(identifier, "localtimestamp");
  }

  private static boolean same(CharSequence actual, String expected) {
    if (actual.length() != expected.length()) return false;
    for (int index = 0; index < actual.length(); index++) {
      if (actual.charAt(index) != expected.charAt(index)) return false;
    }
    return true;
  }
}
