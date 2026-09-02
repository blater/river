package io.riverdb.sql;

/** Reserved-keyword checks used while parsing optional join aliases. */
final class SqlParserJoinAliasRules {
  private SqlParserJoinAliasRules() { }

  static boolean isReserved(SqlParser parser, CharSequence sql) {
    return parser.nextKeyword(sql, "ON")
        || parser.nextKeyword(sql, "USING")
        || parser.nextKeyword(sql, "JOIN")
        || parser.nextKeyword(sql, "INNER")
        || parser.nextKeyword(sql, "LEFT")
        || parser.nextKeyword(sql, "RIGHT")
        || parser.nextKeyword(sql, "FULL")
        || parser.nextKeyword(sql, "CROSS")
        || parser.nextKeyword(sql, "NATURAL")
        || parser.nextKeyword(sql, "OUTER")
        || parser.nextKeyword(sql, "WHERE")
        || parser.nextKeyword(sql, "HAVING")
        || parser.nextKeyword(sql, "GROUP")
        || parser.nextKeyword(sql, "ORDER")
        || parser.nextKeyword(sql, "LIMIT")
        || parser.nextKeyword(sql, "FOR");
  }
}
