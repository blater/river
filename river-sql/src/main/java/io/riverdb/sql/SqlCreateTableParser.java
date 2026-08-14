package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses CREATE TABLE schema columns and delegates other catalog CREATE families. */
final class SqlCreateTableParser {
  private final SqlParser parser;
  private final SqlParserInput input;
  private final SqlCatalogCommandParser catalogCommands;
  private final SqlParser.LongResult numberResult = new SqlParser.LongResult();
  private final ExactDecimal.LongValue decimalResult = new ExactDecimal.LongValue();
  private final SqlIdentifier identifierScratch = new SqlIdentifier();

  SqlCreateTableParser(
      SqlParser parent, SqlParserInput parserInput, SqlCatalogCommandParser catalogParser) {
    parser = parent;
    input = parserInput;
    catalogCommands = catalogParser;
  }

  private StatusCode parseCreateTable(
      CharSequence sql, SqlCommand result) {
    StatusCode status = identifier(sql, result.writableTableName());
    if (!status.isOk()) {
      return status;
    }
    if (!consumeCharacter(sql, '(')) {
      setIdentifier(result.writableNextColumnName(), "key");
      result.markLastColumnNotNull();
      setIdentifier(result.writableNextColumnName(), "value");
      return StatusCode.OK;
    }
    status = parsePrimaryKeyColumn(sql, result);
    while (status.isOk() && !consumeCharacter(sql, ')')) {
      status = requireCharacter(sql, ',');
      if (status.isOk()) {
        status = parseTableColumn(sql, result);
      }
    }
    return status.isOk() && result.columnCount() < 2
        ? StatusCode.INVALID_EXTERNAL_INPUT : status;
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

  private StatusCode parsePrimaryKeyColumn(
      CharSequence sql, SqlCommand result) {
    StatusCode status = columnIdentifier(sql, result);
    if (!status.isOk()) {
      return status;
    }
    status = requireKeyword(sql, "BIGINT");
    if (!status.isOk()) {
      return status;
    }
    if (consumeKeyword(sql, "NOT")) {
      status = requireKeyword(sql, "NULL");
      if (!status.isOk()) {
        return status;
      }
    }
    if (consumeKeyword(sql, "GENERATED")) {
      status = parseIdentityClause(sql, result);
      if (!status.isOk()) {
        return status;
      }
    }
    if (consumeKeyword(sql, "CHECK")) {
      status = columnCheck(sql, result);
      if (!status.isOk()) {
        return status;
      }
    }
    status = requireKeyword(sql, "PRIMARY");
    if (!status.isOk()) {
      return status;
    }
    status = requireKeyword(sql, "KEY");
    if (!status.isOk()) {
      return status;
    }
    result.markLastColumnNotNull();
    return StatusCode.OK;
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
        status = parseColumnDefault(sql, result);
        hasDefault = status.isOk();
      } else if (consumeKeyword(sql, "CHECK")) {
        status = parseColumnCheck(sql, result);
      } else if (consumeKeyword(sql, "UNIQUE")) {
        status = result.markLastColumnUnique();
      } else if (consumeKeyword(sql, "REFERENCES")) {
        status = parseColumnReference(sql, result);
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

  private StatusCode parseColumnDefault(CharSequence sql, SqlCommand result) {
    StatusCode status = literal(sql, numberResult);
    if (status.isOk()) {
      status = coerceLiteral(result.columnTypeDescriptor(result.columnCount() - 1));
    }
    if (status.isOk()) result.markLastColumnDefault(numberResult.value);
    return status;
  }

  private StatusCode coerceLiteral(int targetDescriptor) {
    if (numberResult.typeDescriptor == targetDescriptor
        || SqlTypeDescriptor.typeId(targetDescriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
            && SqlTypeDescriptor.canImplicitlyCast(
                numberResult.typeDescriptor, targetDescriptor)) {
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(targetDescriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        && ExactDecimal.widenScale(
            numberResult.value,
            numberResult.typeDescriptor,
            targetDescriptor,
            decimalResult)) {
      numberResult.value = decimalResult.value;
      numberResult.typeDescriptor = targetDescriptor;
      return StatusCode.OK;
    }
    return SqlTypeDescriptor.canImplicitlyCast(
        numberResult.typeDescriptor, targetDescriptor)
        ? StatusCode.NUMERIC_VALUE_OUT_OF_RANGE : StatusCode.DATATYPE_MISMATCH;
  }

  private StatusCode parseColumnCheck(CharSequence sql, SqlCommand result) {
    return result.columnHasCheck(result.columnCount() - 1)
        ? StatusCode.INVALID_EXTERNAL_INPUT : columnCheck(sql, result);
  }

  private StatusCode parseColumnReference(CharSequence sql, SqlCommand result) {
    return result.columnHasReference(result.columnCount() - 1)
        ? StatusCode.INVALID_EXTERNAL_INPUT : columnReference(sql, result);
  }


  private StatusCode columnIdentifier(CharSequence sql, SqlCommand result) {
    return parser.columnIdentifier(sql, result);
  }

  private StatusCode columnCheck(CharSequence sql, SqlCommand result) {
    StatusCode status = requireCharacter(sql, '(');
    if (!status.isOk()) {
      return status;
    }
    identifierScratch.reset();
    status = identifier(sql, identifierScratch);
    if (!status.isOk()) {
      return status;
    }
    int column = result.columnCount() - 1;
    if (!sameIdentifier(identifierScratch, result.columnName(column))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    SqlComparison comparison = parser.comparisonOperator(sql);
    if (comparison == null
        || comparison == SqlComparison.HALF_OPEN_RANGE
        || comparison == SqlComparison.IN
        || comparison == SqlComparison.NOT_IN) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    status = literal(sql, numberResult);
    if (status.isOk()) {
      status = coerceLiteral(result.columnTypeDescriptor(column));
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    if (status.isOk()) {
      result.markLastColumnCheck(comparison, numberResult.value);
    }
    return status;
  }

  private StatusCode columnReference(CharSequence sql, SqlCommand result) {
    SqlIdentifier table = result.writableLastColumnReferenceTableName();
    SqlIdentifier column = result.writableLastColumnReferenceColumnName();
    if (table == null || column == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = identifier(sql, table);
    if (status.isOk()) {
      status = requireCharacter(sql, '(');
    }
    if (status.isOk()) {
      status = identifier(sql, column);
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    return status.isOk() ? result.markLastColumnReference() : status;
  }

  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    return input.identifier(sql, result);
  }

  private StatusCode literal(CharSequence sql, SqlParser.LongResult result) {
    return input.literal(sql, result);
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

  private static boolean sameIdentifier(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) {
        return false;
      }
    }
    return true;
  }
}
