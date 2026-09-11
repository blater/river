package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses transaction and session commands using the parser's shared cursor. */
final class SqlSessionCommandParser {
  private final SqlParserInput input;
  private final SqlParser.LongResult literalScratch = new SqlParser.LongResult();

  SqlSessionCommandParser(SqlParserInput parserInput) {
    input = parserInput;
  }

  StatusCode parse(CharSequence sql, SqlCommand result) {
    if (input.consumeKeyword(sql, "SET")) return parseSetTimeZone(sql, result);
    if (input.consumeKeyword(sql, "BEGIN")) return parseBegin(sql, result);
    if (input.consumeKeyword(sql, "SAVEPOINT")) {
      return parseNamedCommand(
          sql, result, SqlCommandType.SAVEPOINT, result.writableSavepointName());
    }
    if (input.consumeKeyword(sql, "COMMIT")) {
      result.set(SqlCommandType.COMMIT, 0, 0);
      return StatusCode.OK;
    }
    if (input.consumeKeyword(sql, "ROLLBACK")) return parseRollback(sql, result);
    if (input.consumeKeyword(sql, "RELEASE")) return parseReleaseSavepoint(sql, result);
    if (input.consumeKeyword(sql, "CHECKPOINT")) {
      result.set(SqlCommandType.CHECKPOINT, 0, 0);
      return StatusCode.OK;
    }
    return null;
  }

  private StatusCode parseSetTimeZone(CharSequence sql, SqlCommand result) {
    StatusCode status = input.requireKeyword(sql, "TIME");
    if (status.isOk()) status = input.requireKeyword(sql, "ZONE");
    if (status.isOk()) status = input.packedText(sql, literalScratch);
    if (status.isOk()) {
      result.set(SqlCommandType.SET_TIME_ZONE, 0, literalScratch.value);
    }
    return status;
  }

  private StatusCode parseBegin(CharSequence sql, SqlCommand result) {
    StatusCode status = StatusCode.OK;
    boolean readCommitted = false;
    boolean serializable = false;
    if (input.consumeKeyword(sql, "SERIALIZABLE")) {
      serializable = true;
    } else if (input.consumeKeyword(sql, "READ")) {
      status = input.requireKeyword(sql, "COMMITTED");
      readCommitted = status.isOk();
    } else if (input.consumeKeyword(sql, "REPEATABLE")) {
      status = input.requireKeyword(sql, "READ");
    }
    result.setBegin(readCommitted, serializable);
    return status;
  }

  private StatusCode parseNamedCommand(
      CharSequence sql,
      SqlCommand result,
      SqlCommandType type,
      SqlIdentifier name) {
    result.set(type, 0, 0);
    return input.identifier(sql, name);
  }

  private StatusCode parseRollback(CharSequence sql, SqlCommand result) {
    if (!input.consumeKeyword(sql, "TO")) {
      result.set(SqlCommandType.ROLLBACK, 0, 0);
      return StatusCode.OK;
    }
    input.consumeKeyword(sql, "SAVEPOINT");
    return parseNamedCommand(
        sql,
        result,
        SqlCommandType.ROLLBACK_TO_SAVEPOINT,
        result.writableSavepointName());
  }

  private StatusCode parseReleaseSavepoint(
      CharSequence sql, SqlCommand result) {
    result.set(SqlCommandType.RELEASE_SAVEPOINT, 0, 0);
    StatusCode status = input.requireKeyword(sql, "SAVEPOINT");
    return status.isOk()
        ? input.identifier(sql, result.writableSavepointName()) : status;
  }
}
