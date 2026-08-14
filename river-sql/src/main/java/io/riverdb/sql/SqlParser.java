package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;

/** Allocation-free parser for River's first executable SQL point-statement subset. */
public final class SqlParser {
  private final SqlIdentifier identifierScratch = new SqlIdentifier();
  private final SqlQueryParser queryParser = new SqlQueryParser(this);
  private final SqlParserInput input = new SqlParserInput();
  private final SqlPredicateParser predicateParser = new SqlPredicateParser(input);
  private final SqlScalarExpressionParser scalarExpressions =
      new SqlScalarExpressionParser(input);
  private final SqlSelectParser selects = new SqlSelectParser(this, input, scalarExpressions);
  private final SqlUpdateValueParser updateValues =
      new SqlUpdateValueParser(input);
  private final SqlDataChangeParser dataChanges =
      new SqlDataChangeParser(this, input, updateValues);
  private final SqlCatalogCommandParser catalogCommands =
      new SqlCatalogCommandParser(input);
  private final SqlCreateTableParser creates =
      new SqlCreateTableParser(this, input, catalogCommands);

  public StatusCode parse(String sql, SqlCommand result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    return sql == null ? StatusCode.INVALID_EXTERNAL_INPUT : parseText(sql, result);
  }

  public StatusCode parse(CharSequence sql, SqlCommand result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    return sql == null ? StatusCode.INVALID_EXTERNAL_INPUT : parseText(sql, result);
  }

  public StatusCode parseQuery(
      String sql,
      SqlQuery query,
      SqlCommand result) {
    if (query != null) {
      query.reset();
    }
    if (result != null) {
      result.reset();
    }
    if (sql == null || query == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return queryParser.parse(sql, query, result);
  }

  StatusCode parseQueryBlock(CharSequence sql, SqlCommand result) {
    predicateParser.beginStandard();
    return parseText(sql, result);
  }

  StatusCode parseSyntheticQueryBlock(
      CharSequence sql, int replacementOffset, SqlCommand result) {
    predicateParser.beginSynthetic(replacementOffset);
    return parseText(sql, result);
  }

  int syntheticPredicateIndex() {
    return predicateParser.syntheticPredicateIndex();
  }


  private StatusCode parseText(CharSequence sql, SqlCommand result) {
    input.reset(result);
    skipSpaces(sql);
    StatusCode status = parseStatement(sql, result);
    SqlCommandType type = result.type();
    if (!status.isOk() || !finish(sql)) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    if (catalogCommands.usesReservedObjectName(type, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return result.finish();
  }

  private StatusCode parseStatement(CharSequence sql, SqlCommand result) {
    StatusCode transaction = parseTransactionStatement(sql, result);
    if (transaction != null) return transaction;
    return parseDataStatement(sql, result);
  }

  private StatusCode parseTransactionStatement(CharSequence sql, SqlCommand result) {
    if (consumeKeyword(sql, "BEGIN")) {
      return parseBegin(sql, result);
    }
    if (consumeKeyword(sql, "SAVEPOINT")) {
      return parseNamedCommand(
          sql, result, SqlCommandType.SAVEPOINT, result.writableSavepointName());
    }
    if (consumeKeyword(sql, "COMMIT")) {
      result.set(SqlCommandType.COMMIT, 0, 0);
      return StatusCode.OK;
    }
    if (consumeKeyword(sql, "ROLLBACK")) return parseRollback(sql, result);
    if (consumeKeyword(sql, "RELEASE")) return parseReleaseSavepoint(sql, result);
    if (consumeKeyword(sql, "CHECKPOINT")) {
      result.set(SqlCommandType.CHECKPOINT, 0, 0);
      return StatusCode.OK;
    }
    return null;
  }

  private StatusCode parseDataStatement(CharSequence sql, SqlCommand result) {
    if (consumeKeyword(sql, "SHOW")) return parseShow(sql, result);
    if (consumeKeyword(sql, "ALTER")) return catalogCommands.parseAlter(sql, result);
    if (consumeKeyword(sql, "DROP")) return catalogCommands.parseDrop(sql, result);
    if (consumeKeyword(sql, "CREATE")) return creates.parse(sql, result);
    if (consumeKeyword(sql, "INSERT")) {
      StatusCode status = dataChanges.parseInsert(sql, result);
      if (status.isOk()) result.setInsert();
      return status;
    }
    if (consumeKeyword(sql, "SELECT")) return selects.parse(sql, result);
    if (consumeKeyword(sql, "UPDATE")) {
      StatusCode status = dataChanges.parseUpdate(sql, result);
      if (status.isOk()) result.set(SqlCommandType.UPDATE, 0, result.updateValue(0));
      return status;
    }
    if (consumeKeyword(sql, "DELETE")) {
      StatusCode status = dataChanges.parseDelete(sql, result);
      if (status.isOk()) result.set(SqlCommandType.DELETE, 0, 0);
      return status;
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode parseBegin(CharSequence sql, SqlCommand result) {
    StatusCode status = StatusCode.OK;
    boolean readCommitted = false;
    boolean serializable = false;
    if (consumeKeyword(sql, "SERIALIZABLE")) {
      serializable = true;
    } else if (consumeKeyword(sql, "READ")) {
      status = requireKeyword(sql, "COMMITTED");
      readCommitted = status.isOk();
    } else if (consumeKeyword(sql, "REPEATABLE")) {
      status = requireKeyword(sql, "READ");
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
    return identifier(sql, name);
  }

  private StatusCode parseRollback(CharSequence sql, SqlCommand result) {
    if (!consumeKeyword(sql, "TO")) {
      result.set(SqlCommandType.ROLLBACK, 0, 0);
      return StatusCode.OK;
    }
    consumeKeyword(sql, "SAVEPOINT");
    return parseNamedCommand(
        sql,
        result,
        SqlCommandType.ROLLBACK_TO_SAVEPOINT,
        result.writableSavepointName());
  }

  private StatusCode parseReleaseSavepoint(
      CharSequence sql, SqlCommand result) {
    result.set(SqlCommandType.RELEASE_SAVEPOINT, 0, 0);
    StatusCode status = requireKeyword(sql, "SAVEPOINT");
    return status.isOk()
        ? identifier(sql, result.writableSavepointName()) : status;
  }

  private StatusCode parseShow(CharSequence sql, SqlCommand result) {
    if (consumeKeyword(sql, "TABLES")) {
      result.set(SqlCommandType.SHOW_TABLES, 0, 0);
      return StatusCode.OK;
    }
    result.set(SqlCommandType.SHOW_INDEXES, 0, 0);
    StatusCode status = requireKeyword(sql, "INDEXES");
    if (status.isOk()) {
      status = requireKeyword(sql, "FROM");
    }
    return status.isOk()
        ? identifier(sql, result.writableTableName()) : status;
  }



  StatusCode predicates(CharSequence sql, SqlCommand result, boolean qualified) {
    return predicateParser.parse(sql, result, qualified);
  }

  SqlComparison comparisonOperator(CharSequence sql) {
    return predicateParser.comparisonOperator(sql);
  }

  StatusCode columnIdentifier(CharSequence sql, SqlCommand result) {
    SqlIdentifier column = result.writableNextColumnName();
    return column == null ? StatusCode.RESOURCE_EXHAUSTED : identifier(sql, column);
  }


  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    return input.identifier(sql, result);
  }

  StatusCode optionalTableAlias(
      CharSequence sql,
      SqlCommand result) {
    if (consumeKeyword(sql, "AS")) {
      return identifier(sql, result.writableTableAlias());
    }
    skipSpaces(sql);
    int position = input.position();
    if (position >= sql.length()
        || sql.charAt(position) == ';'
        || sql.charAt(position) == ')'
        || nextKeyword(sql, "LEFT")
        || nextKeyword(sql, "JOIN")
        || nextKeyword(sql, "WHERE")
        || nextKeyword(sql, "GROUP")
        || nextKeyword(sql, "ORDER")
        || nextKeyword(sql, "LIMIT")) {
      return StatusCode.OK;
    }
    return identifierStart(sql.charAt(input.position()))
        ? identifier(sql, result.writableTableAlias())
        : StatusCode.OK;
  }

  StatusCode optionalJoinTableAlias(
      CharSequence sql,
      SqlCommand result) {
    if (consumeKeyword(sql, "AS")) {
      return identifier(sql, result.writableJoinTableAlias());
    }
    skipSpaces(sql);
    if (input.position() >= sql.length()
        || nextKeyword(sql, "ON")) {
      return StatusCode.OK;
    }
    return identifierStart(sql.charAt(input.position()))
        ? identifier(sql, result.writableJoinTableAlias())
        : StatusCode.OK;
  }

  private boolean nextKeyword(CharSequence sql, String keyword) {
    int start = input.position();
    boolean matches = consumeKeyword(sql, keyword);
    input.position(start);
    return matches;
  }

  StatusCode matchingIdentifier(CharSequence sql, CharSequence expected) {
    SqlIdentifier actual = identifierScratch;
    actual.reset();
    StatusCode status = identifier(sql, actual);
    if (!status.isOk()) {
      return status;
    }
    return sameIdentifier(actual, expected)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode matchingEitherIdentifier(
      CharSequence sql,
      CharSequence first,
      CharSequence second) {
    SqlIdentifier actual = identifierScratch;
    actual.reset();
    StatusCode status = identifier(sql, actual);
    if (!status.isOk()) {
      return status;
    }
    return sameIdentifier(actual, first) || sameIdentifier(actual, second)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean sameIdentifier(
      CharSequence left,
      CharSequence right) {
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

  private static void setIdentifier(SqlIdentifier target, String value) {
    for (int index = 0; index < value.length(); index++) {
      target.append(value.charAt(index));
    }
  }

  private StatusCode number(CharSequence sql, LongResult result) {
    return input.number(sql, result);
  }

  private StatusCode literal(CharSequence sql, LongResult result) {
    return input.literal(sql, result);
  }

  private StatusCode packedText(CharSequence sql, LongResult result) {
    return input.packedText(sql, result);
  }

  private boolean startsText(CharSequence sql) {
    return input.startsText(sql);
  }

  private boolean startsNumber(CharSequence sql) {
    return input.startsNumber(sql);
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

  private boolean finish(CharSequence sql) {
    return input.finish(sql);
  }

  private void skipSpaces(CharSequence sql) {
    input.skipSpaces(sql);
  }

  private static char upper(char character) {
    return SqlParserInput.upper(character);
  }

  private static boolean identifierStart(char character) {
    return SqlParserInput.identifierStart(character);
  }

  static final class LongResult {
    long value;
    boolean varchar;
    int textScalars;
    int typeDescriptor;
  }

}
