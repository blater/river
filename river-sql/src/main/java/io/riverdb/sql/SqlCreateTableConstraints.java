package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses inline and table-level key/reference constraints transactionally. */
final class SqlCreateTableConstraints {
  private final SqlParser parser;
  private final SqlParserInput input;
  private final SqlTableCheckConstraintParser checks;
  private final SqlIdentifier name = new SqlIdentifier();
  private final SqlTableKeyPartParser keyParts;

  SqlCreateTableConstraints(
      SqlParser parent,
      SqlParserInput parserInput,
      SqlScalarExpressionParser expressions) {
    parser = parent;
    input = parserInput;
    checks = new SqlTableCheckConstraintParser(parent, parserInput, expressions);
    keyParts = new SqlTableKeyPartParser(parserInput);
  }

  boolean starts(CharSequence sql) {
    return parser.nextKeyword(sql, "CONSTRAINT")
        || parser.nextKeyword(sql, "PRIMARY") || parser.nextKeyword(sql, "UNIQUE")
        || parser.nextKeyword(sql, "FOREIGN") || parser.nextKeyword(sql, "CHECK");
  }

  StatusCode parse(CharSequence sql, SqlCommand command) {
    name.reset();
    if (input.consumeKeyword(sql, "CONSTRAINT")) {
      StatusCode status = input.identifier(sql, name);
      return status.isOk() ? parseConstraint(sql, command, name) : status;
    }
    if (parser.nextKeyword(sql, "PRIMARY") || parser.nextKeyword(sql, "UNIQUE")
        || parser.nextKeyword(sql, "FOREIGN") || parser.nextKeyword(sql, "CHECK")) {
      return parseConstraint(sql, command, null);
    }
    return StatusCode.CONFLICT;
  }

  private StatusCode parseConstraint(
      CharSequence sql, SqlCommand command, CharSequence constraintName) {
    long checkpoint = command.tableConstraints.checkpoint();
    StatusCode status = parseBody(sql, command, constraintName);
    if (!status.isOk()) command.tableConstraints.rollback(checkpoint);
    return status;
  }

  private StatusCode parseBody(
      CharSequence sql, SqlCommand command, CharSequence constraintName) {
    int kind = kind(sql);
    StatusCode status = command.beginTableConstraint(kind);
    if (!status.isOk()) return status;
    if (constraintName != null) {
      command.writableTableConstraintName().copyFrom(constraintName);
    }
    if (kind == SqlTableConstraintSet.CHECK) return checks.parse(sql, command);
    if (kind != SqlTableConstraintSet.UNIQUE) status = input.requireKeyword(sql, "KEY");
    int count = status.isOk() ? keyParts.parse(sql) : -1;
    if (count < 0) return status.isOk()
        ? count == -2 ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.INVALID_EXTERNAL_INPUT
        : status;
    if (kind != SqlTableConstraintSet.FOREIGN) return keyParts.append(command, count);
    status = input.requireKeyword(sql, "REFERENCES");
    if (status.isOk()) {
      status = input.identifier(sql, command.writableTableConstraintReferenceTable());
    }
    int targets = status.isOk() ? keyParts.parseTargets(sql, command, count) : -1;
    return targets == count ? StatusCode.OK
        : status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
  }

  private int kind(CharSequence sql) {
    if (input.consumeKeyword(sql, "PRIMARY")) return SqlTableConstraintSet.PRIMARY;
    if (input.consumeKeyword(sql, "UNIQUE")) return SqlTableConstraintSet.UNIQUE;
    if (input.consumeKeyword(sql, "FOREIGN")) return SqlTableConstraintSet.FOREIGN;
    return input.consumeKeyword(sql, "CHECK") ? SqlTableConstraintSet.CHECK : 0;
  }

}
