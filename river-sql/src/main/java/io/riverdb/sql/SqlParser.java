package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;

/** Allocation-free parser for River's first executable SQL point-statement subset. */
public final class SqlParser {
  private final SqlIdentifier identifierScratch = new SqlIdentifier();
  private final LongResult literalScratch = new LongResult();
  private final SqlQueryParser queryParser = new SqlQueryParser(this);
  private final SqlParserInput input = new SqlParserInput();
  private final SqlScalarExpressionParser scalarExpressions =
      new SqlScalarExpressionParser(input);
  private final SqlPredicateParser predicateParser =
      new SqlPredicateParser(input, scalarExpressions);
  private final SqlSelectParser selects = new SqlSelectParser(this, input, scalarExpressions);
  private final SqlUpdateValueParser updateValues =
      new SqlUpdateValueParser(input, scalarExpressions);
  private final SqlDataChangeParser dataChanges =
      new SqlDataChangeParser(this, input, updateValues);
  private final SqlCatalogCommandParser catalogCommands =
      new SqlCatalogCommandParser(input);
  private final SqlCreateTableParser creates =
      new SqlCreateTableParser(this, input, catalogCommands, scalarExpressions);

  public StatusCode parse(String sql, SqlCommand result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    return sql == null ? StatusCode.INVALID_EXTERNAL_INPUT : parseText(sql, result);
  }

  public StatusCode parse(
      String sql, SqlParameterSource parameters, SqlCommand result) {
    if (result == null || parameters == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (sql == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      StatusCode status = SqlParameterAdmission.beginData(sql, parameters, input);
      if (status.isOk()) status = parseText(sql, result);
      return SqlParameterAdmission.finish(status, input);
    } finally {
      input.clearParameters();
    }
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
    return parseQuery((CharSequence) sql, query, result);
  }

  public StatusCode parseQueryAppend(
      CharSequence sql, SqlQuery query, SqlCommand result) {
    StatusCode status = queryParser.parseAppend(sql, query, result);
    if (!status.isOk() && query != null) query.discardJoinChains();
    return status;
  }

  public int queryBlockDepth(CharSequence sql) {
    return queryParser.blockDepth(sql);
  }

  public StatusCode parseQuery(
      String sql,
      SqlParameterSource parameters,
      SqlQuery query,
      SqlCommand result) {
    if (query != null) {
      query.reset();
    }
    if (result != null) {
      result.reset();
    }
    if (sql == null || parameters == null || query == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      StatusCode status = SqlParameterAdmission.beginQuery(
          sql, parameters, input, queryParser);
      if (status.isOk()) status = queryParser.parse(sql, query, result);
      status = SqlParameterAdmission.finish(status, input);
      if (!status.isOk()) {
        query.discardJoinChains();
        query.reset();
      }
      return status;
    } finally {
      input.clearParameters();
    }
  }

  public StatusCode parseQuery(
      CharSequence sql,
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
    StatusCode status = queryParser.parse(sql, query, result);
    if (!status.isOk()) {
      query.discardJoinChains();
      query.reset();
    }
    return status;
  }

  StatusCode parseQueryBlock(CharSequence sql, SqlCommand result) {
    predicateParser.beginStandard();
    return parseText(sql, result);
  }

  StatusCode parseSubqueryBlock(
      CharSequence sql,
      int[] offsets,
      int[] kinds,
      int[] edges,
      int count,
      SqlCommand result) {
    if (offsets == null || kinds == null || edges == null || result == null
        || count < 0 || count > SqlBooleanPredicateProgram.MAXIMUM_LEAVES
        || count > offsets.length || count > kinds.length || count > edges.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    predicateParser.beginSubqueries(offsets, kinds, edges, count);
    return parseText(sql, result);
  }

  int subqueryLeaf(int index) {
    return predicateParser.subqueryLeaf(index);
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
    if (consumeKeyword(sql, "SET")) {
      return parseSetTimeZone(sql, result);
    }
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

  private StatusCode parseSetTimeZone(CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "TIME");
    if (status.isOk()) {
      status = requireKeyword(sql, "ZONE");
    }
    LongResult zone = literalScratch;
    if (status.isOk()) {
      status = input.packedText(sql, zone);
    }
    if (status.isOk()) {
      result.set(SqlCommandType.SET_TIME_ZONE, 0, zone.value);
    }
    return status;
  }

  private StatusCode parseDataStatement(CharSequence sql, SqlCommand result) {
    StatusCode catalog = parseCatalogStatement(sql, result);
    if (catalog != null) return catalog;
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

  private StatusCode parseCatalogStatement(CharSequence sql, SqlCommand result) {
    if (consumeKeyword(sql, "ANALYZE")) return parseAnalyze(sql, result);
    if (consumeKeyword(sql, "SHOW")) return parseShow(sql, result);
    if (consumeKeyword(sql, "ALTER")) return catalogCommands.parseAlter(sql, result);
    if (consumeKeyword(sql, "DROP")) return catalogCommands.parseDrop(sql, result);
    return consumeKeyword(sql, "CREATE") ? creates.parse(sql, result) : null;
  }

  private StatusCode parseAnalyze(CharSequence sql, SqlCommand result) {
    consumeKeyword(sql, "TABLE");
    result.set(SqlCommandType.ANALYZE_TABLE, 0, 0);
    return identifier(sql, result.writableTableName());
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
    SqlCommandType type;
    if (consumeKeyword(sql, "INDEXES")) {
      type = SqlCommandType.SHOW_INDEXES;
    } else if (consumeKeyword(sql, "COLUMNS")) {
      type = SqlCommandType.SHOW_COLUMNS;
    } else {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.set(type, 0, 0);
    StatusCode status = requireKeyword(sql, "FROM");
    return status.isOk()
        ? identifier(sql, result.writableTableName()) : status;
  }



  StatusCode predicates(CharSequence sql, SqlCommand result, boolean qualified) {
    return predicateParser.parse(sql, result, qualified);
  }

  StatusCode joinPredicates(
      CharSequence sql,
      SqlCommand result,
      SqlBooleanPredicateProgram destination) {
    return predicateParser.parseOn(sql, result, destination);
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
      if (joinAliasReserved(sql)) return StatusCode.INVALID_EXTERNAL_INPUT;
      return identifier(sql, result.writableTableAlias());
    }
    skipSpaces(sql);
    int position = input.position();
    if (position >= sql.length()
        || sql.charAt(position) == ';'
        || sql.charAt(position) == ')'
        || joinAliasReserved(sql)) {
      return StatusCode.OK;
    }
    return identifierStart(sql.charAt(input.position()))
        ? identifier(sql, result.writableTableAlias())
        : StatusCode.OK;
  }

  StatusCode optionalJoinTableAlias(
      CharSequence sql,
      SqlIdentifier alias) {
    if (consumeKeyword(sql, "AS")) {
      if (joinAliasReserved(sql)) return StatusCode.INVALID_EXTERNAL_INPUT;
      return identifier(sql, alias);
    }
    skipSpaces(sql);
    if (input.position() >= sql.length()
        || joinAliasReserved(sql)) {
      return StatusCode.OK;
    }
    return identifierStart(sql.charAt(input.position()))
        ? identifier(sql, alias)
        : StatusCode.OK;
  }

  private boolean joinAliasReserved(CharSequence sql) {
    return nextKeyword(sql, "ON")
        || nextKeyword(sql, "USING")
        || nextKeyword(sql, "JOIN")
        || nextKeyword(sql, "INNER")
        || nextKeyword(sql, "LEFT")
        || nextKeyword(sql, "RIGHT")
        || nextKeyword(sql, "FULL")
        || nextKeyword(sql, "CROSS")
        || nextKeyword(sql, "NATURAL")
        || nextKeyword(sql, "OUTER")
        || nextKeyword(sql, "WHERE")
        || nextKeyword(sql, "HAVING")
        || nextKeyword(sql, "GROUP")
        || nextKeyword(sql, "ORDER")
        || nextKeyword(sql, "LIMIT");
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
    boolean nullValue;
    int textScalars;
    int typeDescriptor;
  }

}
