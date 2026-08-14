package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses one bounded INNER or LEFT JOIN and its equality edge. */
final class SqlJoinParser {
  private final SqlParser parser;
  private final SqlParserInput input;

  SqlJoinParser(SqlParser parent, SqlParserInput parserInput) {
    parser = parent;
    input = parserInput;
  }

  StatusCode parseOptional(CharSequence sql, SqlCommand result) {
    boolean left = input.consumeKeyword(sql, "LEFT");
    if (left) {
      input.consumeKeyword(sql, "OUTER");
      StatusCode status = input.requireKeyword(sql, "JOIN");
      if (!status.isOk()) {
        return status;
      }
    } else if (!input.consumeKeyword(sql, "JOIN")) {
      return StatusCode.OK;
    }
    result.set(SqlCommandType.JOIN_SCAN, 0, 0);
    if (left) {
      result.setLeftJoin();
    }
    StatusCode status = input.identifier(sql, result.writableJoinTableName());
    if (status.isOk()) {
      status = parser.optionalJoinTableAlias(sql, result);
    }
    if (status.isOk()) {
      status = input.requireKeyword(sql, "ON");
    }
    return status.isOk() ? parseEquality(sql, result) : status;
  }

  private StatusCode parseEquality(CharSequence sql, SqlCommand result) {
    StatusCode status = parser.matchingEitherIdentifier(
        sql, result.tableName(), result.tableAlias());
    if (status.isOk()) {
      status = input.requireCharacter(sql, '.');
    }
    if (status.isOk()) {
      status = input.identifier(sql, result.writableJoinOuterColumnName());
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, '=');
    }
    if (status.isOk()) {
      status = parser.matchingEitherIdentifier(
          sql, result.joinTableName(), result.joinTableAlias());
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, '.');
    }
    return status.isOk()
        ? input.identifier(sql, result.writableJoinInnerColumnName()) : status;
  }
}
