package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses CREATE TABLE schema columns and delegates other catalog CREATE families. */
final class SqlCreateTableParser {
  private final SqlParser parser;
  private final SqlParserInput input;
  private final SqlCatalogCommandParser catalogCommands;
  private final SqlColumnCheckParser checks;
  private final SqlParser.LongResult numberResult = new SqlParser.LongResult();
  private final SqlColumnDefaultParser defaults;
  private final SqlCreateTableConstraints constraints;
  private final SqlInlineColumnConstraints inlineConstraints;

  SqlCreateTableParser(
      SqlParser parent,
      SqlParserInput parserInput,
      SqlCatalogCommandParser catalogParser,
      SqlScalarExpressionParser expressionParser) {
    parser = parent;
    input = parserInput;
    catalogCommands = catalogParser;
    checks = new SqlColumnCheckParser(parent, parserInput, expressionParser);
    constraints = new SqlCreateTableConstraints(parent, parserInput, expressionParser);
    inlineConstraints = new SqlInlineColumnConstraints(parserInput);
    defaults = new SqlColumnDefaultParser(parserInput);
  }

  private StatusCode parseCreateTable(
      CharSequence sql, SqlCommand result) {
    StatusCode status = identifier(sql, result.writableTableName());
    if (!status.isOk()) {
      return status;
    }
    if (!consumeCharacter(sql, '(')) {
      setIdentifier(result.writableNextColumnName(), "key");
      status = inlinePrimary(result);
      if (status.isOk()) setIdentifier(result.writableNextColumnName(), "value");
      return status;
    }
    status = parseTableElement(sql, result);
    while (status.isOk() && !consumeCharacter(sql, ')')) {
      status = requireCharacter(sql, ',');
      if (status.isOk()) {
        status = parseTableElement(sql, result);
      }
    }
    return status.isOk() ? SqlTableConstraintValidation.validate(result) : status;
  }

  StatusCode parse(CharSequence sql, SqlCommand result) {
    if (consumeKeyword(sql, "VIEW")) {
      return catalogCommands.parseCreateView(sql, result);
    }
    if (consumeKeyword(sql, "TABLE")) {
      result.set(SqlCommandType.CREATE_TABLE, 0, 0);
      return parseCreateTable(sql, result);
    }
    return catalogCommands.parseCreateRemainder(sql, result);
  }

  private StatusCode parseTableElement(CharSequence sql, SqlCommand result) {
    return constraints.starts(sql)
        ? constraints.parse(sql, result) : parseTableColumn(sql, result);
  }

  private StatusCode parseIdentityClause(
      CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "ALWAYS");
    if (status.isOk()) {
      status = requireKeyword(sql, "AS");
    }
    if (status.isOk()) {
      status = requireKeyword(sql, "IDENTITY");
    }
    if (status.isOk()) {
      result.markPrimaryKeyIdentity();
    }
    return status;
  }

  private StatusCode parseTableColumn(
      CharSequence sql, SqlCommand result) {
    StatusCode status = columnIdentifier(sql, result);
    if (status.isOk()) {
      status = parseColumnType(sql, result);
    }
    return status.isOk() ? parseColumnConstraints(sql, result) : status;
  }

  private StatusCode parseColumnType(
      CharSequence sql, SqlCommand result) {
    StatusCode status = input.typeDescriptor(sql, numberResult);
    if (status.isOk()) {
      result.markLastColumnType((int) numberResult.value);
    }
    return status;
  }

  private StatusCode parseColumnConstraints(
      CharSequence sql, SqlCommand result) {
    StatusCode status = StatusCode.OK;
    boolean notNull = false;
    boolean hasDefault = false;
    while (status.isOk()) {
      if (consumeKeyword(sql, "NOT")) {
        if (notNull) return StatusCode.INVALID_EXTERNAL_INPUT;
        status = parseNotNull(sql, result);
        notNull = status.isOk();
      } else if (consumeKeyword(sql, "DEFAULT")) {
        if (hasDefault) return StatusCode.INVALID_EXTERNAL_INPUT;
        status = defaults.parse(sql, result);
        hasDefault = status.isOk();
      } else if (consumeKeyword(sql, "CHECK")) {
        status = parseColumnCheck(sql, result);
      } else if (consumeKeyword(sql, "UNIQUE")) {
        status = inlineConstraints.unique(result);
      } else if (consumeKeyword(sql, "REFERENCES")) {
        status = inlineConstraints.reference(sql, result);
      } else if (consumeKeyword(sql, "GENERATED")) {
        status = result.hasPrimaryKeyIdentity()
            ? StatusCode.INVALID_EXTERNAL_INPUT : parseIdentityClause(sql, result);
      } else if (consumeKeyword(sql, "PRIMARY")) {
        status = requireKeyword(sql, "KEY");
        if (status.isOk()) status = inlineConstraints.primary(result);
      } else {
        break;
      }
    }
    return status;
  }

  private StatusCode parseNotNull(CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "NULL");
    if (status.isOk()) result.markLastColumnNotNull();
    return status;
  }

  private StatusCode parseColumnCheck(CharSequence sql, SqlCommand result) {
    if (result.columnHasCheck(result.columnCount() - 1)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = columnCheck(sql, result);
    long checkpoint = result.tableConstraints.checkpoint();
    if (status.isOk()) status = result.beginTableConstraint(SqlTableConstraintSet.CHECK);
    status = status.isOk()
        ? result.addTableConstraintPart(result.columnName(result.columnCount() - 1), null) : status;
    if (!status.isOk()) result.tableConstraints.rollback(checkpoint);
    return status;
  }

  private StatusCode inlinePrimary(SqlCommand result) {
    return inlineConstraints.primary(result);
  }


  private StatusCode columnIdentifier(CharSequence sql, SqlCommand result) {
    return parser.columnIdentifier(sql, result);
  }

  private StatusCode columnCheck(CharSequence sql, SqlCommand result) {
    return checks.parse(sql, result);
  }

  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    return input.identifier(sql, result);
  }

  private StatusCode requireKeyword(CharSequence sql, String keyword) {
    return input.requireKeyword(sql, keyword);
  }

  private boolean consumeKeyword(CharSequence sql, String keyword) {
    return input.consumeKeyword(sql, keyword);
  }

  private StatusCode requireCharacter(CharSequence sql, char expected) {
    return input.requireCharacter(sql, expected);
  }

  private boolean consumeCharacter(CharSequence sql, char expected) {
    return input.consumeCharacter(sql, expected);
  }

  private static void setIdentifier(SqlIdentifier target, String value) {
    for (int index = 0; index < value.length(); index++) {
      target.append(value.charAt(index));
    }
  }

}
