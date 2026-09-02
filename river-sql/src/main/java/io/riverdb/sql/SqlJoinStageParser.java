package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses one right-hand table and ON predicate into an admitted JOIN stage. */
final class SqlJoinStageParser {
  private final SqlParser parser;
  private final SqlParserInput input;

  SqlJoinStageParser(SqlParser parent, SqlParserInput parserInput) {
    parser = parent;
    input = parserInput;
  }

  StatusCode parse(
      CharSequence sql, SqlCommand command, SqlJoinChain chain, int kind) {
    int stage = chain.appendStage(kind == SqlJoinChain.LEFT);
    if (stage < 0) return StatusCode.RESOURCE_EXHAUSTED;
    int role = chain.rightRole(stage);
    if (input.consumeCharacter(sql, '(')) return StatusCode.FEATURE_NOT_SUPPORTED;
    StatusCode status = input.identifier(sql, chain.writableTableName(role));
    if (status.isOk()) {
      status = parser.optionalJoinTableAlias(sql, chain.writableAlias(role));
    }
    if (status.isOk() && input.consumeKeyword(sql, "USING")) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (status.isOk()) status = input.requireKeyword(sql, "ON");
    if (status.isOk()) {
      SqlBooleanPredicateProgram predicates = chain.writableOnPredicates(stage);
      status = predicates == null ? StatusCode.RESOURCE_EXHAUSTED
          : parser.joinPredicates(sql, command, predicates);
    }
    return status.isOk() ? chain.validateStage(stage) : status;
  }
}
