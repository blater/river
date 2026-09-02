package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses a bounded left-associative INNER/LEFT JOIN chain. */
final class SqlJoinParser {
  private final SqlJoinKindReader kinds;
  private final SqlJoinStageParser stages;

  SqlJoinParser(SqlParser parent, SqlParserInput parserInput) {
    kinds = new SqlJoinKindReader(parserInput);
    stages = new SqlJoinStageParser(parent, parserInput);
  }

  StatusCode parseOptional(CharSequence sql, SqlCommand result) {
    SqlJoinChain chain = null;
    while (true) {
      int kind = kinds.read(sql);
      if (kind == 0) return StatusCode.OK;
      if (kind < 0) return StatusCode.FEATURE_NOT_SUPPORTED;
      if (kind > SqlJoinChain.LEFT) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (chain == null) {
        StatusCode status = result.beginJoinChain();
        if (!status.isOk()) return status;
        chain = result.writableJoinChain();
      }
      StatusCode status = stages.parse(sql, result, chain, kind);
      if (!status.isOk()) return status;
      result.set(SqlCommandType.JOIN_SCAN, 0, 0);
    }
  }
}
