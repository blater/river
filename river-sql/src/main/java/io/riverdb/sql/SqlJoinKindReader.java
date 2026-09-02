package io.riverdb.sql;

/** Consumes one supported or explicitly unsupported JOIN introducer. */
final class SqlJoinKindReader {
  static final int UNSUPPORTED = -1;
  static final int MALFORMED = 3;

  private final SqlParserInput input;

  SqlJoinKindReader(SqlParserInput parserInput) {
    input = parserInput;
  }

  int read(CharSequence sql) {
    if (input.consumeKeyword(sql, "LEFT")) {
      input.consumeKeyword(sql, "OUTER");
      return input.consumeKeyword(sql, "JOIN") ? SqlJoinChain.LEFT : MALFORMED;
    }
    if (input.consumeKeyword(sql, "INNER")) {
      return input.consumeKeyword(sql, "JOIN") ? SqlJoinChain.INNER : MALFORMED;
    }
    if (input.consumeKeyword(sql, "JOIN")) return SqlJoinChain.INNER;
    return unsupported(sql) ? UNSUPPORTED : 0;
  }

  private boolean unsupported(CharSequence sql) {
    return input.consumeKeyword(sql, "RIGHT")
        || input.consumeKeyword(sql, "FULL")
        || input.consumeKeyword(sql, "CROSS")
        || input.consumeKeyword(sql, "NATURAL");
  }
}
