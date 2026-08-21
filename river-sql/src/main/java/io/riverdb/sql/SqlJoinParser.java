package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses a bounded left-associative INNER/LEFT JOIN chain. */
final class SqlJoinParser {
  private final SqlParser parser;
  private final SqlParserInput input;

  SqlJoinParser(SqlParser parent, SqlParserInput parserInput) {
    parser = parent;
    input = parserInput;
  }

  StatusCode parseOptional(CharSequence sql, SqlCommand result) {
    SqlJoinChain chain = null;
    while (true) {
      int kind = joinKind(sql);
      if (kind == 0) return StatusCode.OK;
      if (kind < 0) return StatusCode.FEATURE_NOT_SUPPORTED;
      if (kind > SqlJoinChain.LEFT) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (chain == null) chain = result.beginJoinChain();
      int stage = chain.appendStage(kind == SqlJoinChain.LEFT);
      if (stage < 0) return StatusCode.RESOURCE_EXHAUSTED;
      int role = chain.rightRole(stage);
      if (input.consumeCharacter(sql, '(')) return StatusCode.FEATURE_NOT_SUPPORTED;
      StatusCode status = input.identifier(sql, chain.writableTableName(role));
      if (status.isOk()) status = parser.optionalJoinTableAlias(sql, chain.writableAlias(role));
      if (status.isOk() && input.consumeKeyword(sql, "USING")) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
      if (status.isOk()) status = input.requireKeyword(sql, "ON");
      if (status.isOk()) {
        status = parser.joinPredicates(sql, result, chain.writableOnPredicates(stage));
      }
      if (status.isOk()) status = chain.validateStage(stage);
      if (!status.isOk()) return status;
      result.set(SqlCommandType.JOIN_SCAN, 0, 0);
    }
  }

  private int joinKind(CharSequence sql) {
    if (input.consumeKeyword(sql, "LEFT")) {
      input.consumeKeyword(sql, "OUTER");
      return input.consumeKeyword(sql, "JOIN") ? SqlJoinChain.LEFT : 3;
    }
    if (input.consumeKeyword(sql, "INNER")) {
      return input.consumeKeyword(sql, "JOIN") ? SqlJoinChain.INNER : 3;
    }
    if (input.consumeKeyword(sql, "JOIN")) return SqlJoinChain.INNER;
    if (input.consumeKeyword(sql, "RIGHT")
        || input.consumeKeyword(sql, "FULL")
        || input.consumeKeyword(sql, "CROSS")
        || input.consumeKeyword(sql, "NATURAL")) return -1;
    return 0;
  }
}
