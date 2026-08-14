package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Allocation-free parser for River's first executable SQL point-statement subset. */
public final class SqlParser {
  private final LongResult numberResult = new LongResult();
  private final ExactDecimal.LongValue decimalResult = new ExactDecimal.LongValue();
  private final LongRow rowResult = new LongRow();
  private final SqlIdentifier identifierScratch = new SqlIdentifier();
  private final SqlQueryParser queryParser = new SqlQueryParser(this);
  private final PredicateResult predicateResult = new PredicateResult();
  private final long[] literalMembershipValues =
      new long[SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES];
  private final SqlParserInput input = new SqlParserInput();
  private final SqlScalarExpressionParser scalarExpressions =
      new SqlScalarExpressionParser(input);
  private final SqlUpdateValueParser updateValues =
      new SqlUpdateValueParser(input);
  private final SqlCatalogCommandParser catalogCommands =
      new SqlCatalogCommandParser(input);
  private int syntheticPredicateOffset = -1;
  private int syntheticPredicateIndex = -1;

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
    syntheticPredicateOffset = -1;
    syntheticPredicateIndex = -1;
    return parseText(sql, result);
  }

  StatusCode parseSyntheticQueryBlock(
      CharSequence sql, int replacementOffset, SqlCommand result) {
    syntheticPredicateOffset = replacementOffset;
    syntheticPredicateIndex = -1;
    return parseText(sql, result);
  }

  int syntheticPredicateIndex() {
    return syntheticPredicateIndex;
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
    if (consumeKeyword(sql, "CREATE")) return parseCreate(sql, result);
    if (consumeKeyword(sql, "INSERT")) {
      StatusCode status = parseInsert(sql, result);
      if (status.isOk()) result.setInsert();
      return status;
    }
    if (consumeKeyword(sql, "SELECT")) return parseSelect(sql, result);
    if (consumeKeyword(sql, "UPDATE")) {
      StatusCode status = parseUpdate(sql, result);
      if (status.isOk()) result.set(SqlCommandType.UPDATE, 0, result.updateValue(0));
      return status;
    }
    if (consumeKeyword(sql, "DELETE")) {
      StatusCode status = parseDelete(sql, result);
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

  private StatusCode parseInsert(CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "INTO");
    if (status.isOk()) {
      status = identifier(sql, result.writableTableName());
    }
    if (status.isOk() && consumeCharacter(sql, '(')) {
      status = insertColumns(sql, result);
    }
    if (status.isOk()) {
      status = requireKeyword(sql, "VALUES");
    }
    if (status.isOk()) {
      status = appendInsertRow(sql, result, false);
    }
    while (status.isOk() && consumeCharacter(sql, ',')) {
      status = appendInsertRow(sql, result, true);
    }
    return status;
  }

  private StatusCode parseSelect(CharSequence sql, SqlCommand result) {
    if (consumeKeyword(sql, "NEXT")) {
      result.set(SqlCommandType.NEXT_SEQUENCE_VALUE, 0, 0);
      StatusCode status = requireKeyword(sql, "VALUE");
      if (status.isOk()) {
        status = requireKeyword(sql, "FOR");
      }
      return status.isOk()
          ? identifier(sql, result.writableSequenceName()) : status;
    }
    if (consumeKeyword(sql, "COUNT")) {
      return parseCount(sql, result);
    }
    if (consumeKeyword(sql, "SUM")) {
      result.set(SqlCommandType.SUM, 0, 0);
      return valueAggregate(sql, result);
    }
    if (consumeKeyword(sql, "AVG")) {
      result.set(SqlCommandType.AVG, 0, 0);
      return valueAggregate(sql, result);
    }
    if (consumeKeyword(sql, "MIN")) {
      result.set(SqlCommandType.MIN, 0, 0);
      return valueAggregate(sql, result);
    }
    if (consumeKeyword(sql, "MAX")) {
      result.set(SqlCommandType.MAX, 0, 0);
      return valueAggregate(sql, result);
    }
    if (scalarExpressions.starts(sql)) {
      result.set(SqlCommandType.SCALAR_EXPRESSION, 0, 0);
      return scalarExpressions.parse(sql, result.scalarExpression());
    }
    return parseRowSelect(sql, result);
  }

  private StatusCode parseCount(CharSequence sql, SqlCommand result) {
    result.set(SqlCommandType.COUNT, 0, 0);
    StatusCode status = requireCharacter(sql, '(');
    if (status.isOk() && consumeCharacter(sql, '*')) {
      status = requireCharacter(sql, ')');
    } else if (status.isOk()) {
      result.set(SqlCommandType.COUNT_VALUE, 0, 0);
      status = aggregateColumn(sql, result);
    }
    return status.isOk() ? aggregateSource(sql, result) : status;
  }

  private StatusCode parseRowSelect(
      CharSequence sql, SqlCommand result) {
    boolean distinct = consumeKeyword(sql, "DISTINCT");
    result.set(
        distinct ? SqlCommandType.DISTINCT_SCAN : SqlCommandType.SCAN,
        0,
        0);
    StatusCode status = parseSelectProjection(sql, result, distinct);
    if (!status.isOk()) {
      return status;
    }
    status = requireKeyword(sql, "FROM");
    if (!status.isOk()) {
      return status;
    }
    status = identifier(sql, result.writableTableName());
    if (!status.isOk()) {
      return status;
    }
    status = optionalTableAlias(sql, result);
    if (!status.isOk()) {
      return status;
    }
    if (!isGroupAggregate(result.type())) {
      status = parseOptionalJoin(sql, result);
    }
    if (!status.isOk()) {
      return status;
    }
    if (consumeKeyword(sql, "WHERE")) {
      status = predicates(
          sql, result, result.type() == SqlCommandType.JOIN_SCAN);
    }
    if (!status.isOk()) {
      return status;
    }
    status = parseGroupOrPointSelection(sql, result);
    if (!status.isOk()) {
      return status;
    }
    status = parseOrder(sql, result);
    return status.isOk() ? parseLimit(sql, result) : status;
  }

  private StatusCode parseSelectProjection(
      CharSequence sql, SqlCommand result, boolean distinct) {
    if (!distinct && consumeCharacter(sql, '*')) {
      result.setSelectAll();
      return StatusCode.OK;
    }
    StatusCode status = selectColumnIdentifier(sql, result);
    if (distinct || !status.isOk() || !consumeCharacter(sql, ',')) {
      return status;
    }
    if (consumeKeyword(sql, "COUNT")) {
      return parseGroupedCount(sql, result);
    }
    if (consumeKeyword(sql, "SUM")) {
      result.set(SqlCommandType.GROUP_SUM, 0, 0);
      return parseGroupedValueAggregate(sql, result);
    }
    if (consumeKeyword(sql, "AVG")) {
      result.set(SqlCommandType.GROUP_AVG, 0, 0);
      return parseGroupedValueAggregate(sql, result);
    }
    if (consumeKeyword(sql, "MIN")) {
      result.set(SqlCommandType.GROUP_MIN, 0, 0);
      return parseGroupedValueAggregate(sql, result);
    }
    if (consumeKeyword(sql, "MAX")) {
      result.set(SqlCommandType.GROUP_MAX, 0, 0);
      return parseGroupedValueAggregate(sql, result);
    }
    status = selectColumnIdentifier(sql, result);
    while (status.isOk() && consumeCharacter(sql, ',')) {
      status = selectColumnIdentifier(sql, result);
    }
    return status;
  }

  private StatusCode parseGroupedCount(
      CharSequence sql, SqlCommand result) {
    result.set(SqlCommandType.GROUP_COUNT, 0, 0);
    StatusCode status = requireCharacter(sql, '(');
    if (status.isOk() && consumeCharacter(sql, '*')) {
      return requireCharacter(sql, ')');
    }
    if (status.isOk()) {
      result.set(SqlCommandType.GROUP_COUNT_VALUE, 0, 0);
      status = groupAggregateColumn(sql, result);
    }
    return status;
  }

  private StatusCode parseGroupedValueAggregate(
      CharSequence sql, SqlCommand result) {
    StatusCode status = requireCharacter(sql, '(');
    return status.isOk() ? groupAggregateColumn(sql, result) : status;
  }

  private StatusCode parseOptionalJoin(
      CharSequence sql, SqlCommand result) {
    boolean left = consumeKeyword(sql, "LEFT");
    if (left) {
      consumeKeyword(sql, "OUTER");
      StatusCode status = requireKeyword(sql, "JOIN");
      if (!status.isOk()) {
        return status;
      }
    } else if (!consumeKeyword(sql, "JOIN")) {
      return StatusCode.OK;
    }
    result.set(SqlCommandType.JOIN_SCAN, 0, 0);
    if (left) {
      result.setLeftJoin();
    }
    StatusCode status = identifier(sql, result.writableJoinTableName());
    if (!status.isOk()) {
      return status;
    }
    status = optionalJoinTableAlias(sql, result);
    if (!status.isOk()) {
      return status;
    }
    status = requireKeyword(sql, "ON");
    return status.isOk() ? parseJoinEquality(sql, result) : status;
  }

  private StatusCode parseJoinEquality(
      CharSequence sql, SqlCommand result) {
    StatusCode status = matchingEitherIdentifier(
        sql, result.tableName(), result.tableAlias());
    if (!status.isOk()) {
      return status;
    }
    status = requireCharacter(sql, '.');
    if (!status.isOk()) {
      return status;
    }
    status = identifier(sql, result.writableJoinOuterColumnName());
    if (!status.isOk()) {
      return status;
    }
    status = requireCharacter(sql, '=');
    if (!status.isOk()) {
      return status;
    }
    status = matchingEitherIdentifier(
        sql, result.joinTableName(), result.joinTableAlias());
    if (!status.isOk()) {
      return status;
    }
    status = requireCharacter(sql, '.');
    return status.isOk()
        ? identifier(sql, result.writableJoinInnerColumnName()) : status;
  }

  private StatusCode parseGroupOrPointSelection(
      CharSequence sql, SqlCommand result) {
    SqlCommandType type = result.type();
    if (isGroupAggregate(type)) {
      StatusCode status = requireKeyword(sql, "GROUP");
      if (status.isOk()) {
        status = requireKeyword(sql, "BY");
      }
      if (status.isOk()) {
        status = matchingIdentifier(sql, result.firstColumnName());
      }
      if (status.isOk() && consumeKeyword(sql, "HAVING")) {
        status = groupHaving(sql, result, type);
      }
      return status;
    }
    if (type == SqlCommandType.SCAN
        && result.hasPredicate()
        && result.isEqualityPredicate()) {
      result.set(SqlCommandType.SELECT, 0, 0);
    }
    return StatusCode.OK;
  }

  private StatusCode parseOrder(CharSequence sql, SqlCommand result) {
    SqlCommandType type = result.type();
    if (type == SqlCommandType.JOIN_SCAN || !consumeKeyword(sql, "ORDER")) {
      return StatusCode.OK;
    }
    StatusCode status = requireKeyword(sql, "BY");
    if (status.isOk()) {
      status = isGroupAggregate(type) || type == SqlCommandType.DISTINCT_SCAN
          ? matchingEitherIdentifier(
              sql, result.firstColumnName(), result.columnOutputName(0))
          : identifier(sql, result.writableOrderColumnName());
    }
    if (status.isOk() && consumeKeyword(sql, "ASC")) {
      result.setDescendingOrder(false);
    } else if (status.isOk() && consumeKeyword(sql, "DESC")) {
      if (isGroupAggregate(type) || type == SqlCommandType.DISTINCT_SCAN) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      } else {
        result.setDescendingOrder(true);
      }
    }
    return status;
  }

  private StatusCode parseLimit(CharSequence sql, SqlCommand result) {
    if (!consumeKeyword(sql, "LIMIT")) {
      return StatusCode.OK;
    }
    StatusCode status = number(sql, numberResult);
    if (status.isOk() && numberResult.value < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      result.setRowLimit(numberResult.value);
    }
    return status;
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

  private StatusCode parseCreate(CharSequence sql, SqlCommand result) {
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

  private StatusCode insertColumns(CharSequence sql, SqlCommand result) {
    StatusCode status = StatusCode.OK;
    while (status.isOk()) {
      status = columnIdentifier(sql, result);
      if (!status.isOk() || consumeCharacter(sql, ')')) {
        return status;
      }
      status = requireCharacter(sql, ',');
    }
    return status;
  }

  private StatusCode appendInsertRow(
      CharSequence sql, SqlCommand result, boolean subsequent) {
    if (subsequent
        && result.insertRowCount() >= SqlCommand.MAXIMUM_INSERT_ROWS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = row(sql, rowResult);
    if (status.isOk()
        && subsequent
        && rowResult.count != result.insertColumnCount()) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      result.appendInsert(
          rowResult.values,
          rowResult.nullMask,
          rowResult.defaultMask,
          rowResult.typeDescriptors,
          rowResult.count);
    }
    return status;
  }

  private StatusCode parseUpdate(CharSequence sql, SqlCommand result) {
    StatusCode status = identifier(sql, result.writableTableName());
    if (status.isOk()) {
      status = requireKeyword(sql, "SET");
    }
    while (status.isOk()) {
      status = appendUpdate(sql, result);
      if (!status.isOk() || !consumeCharacter(sql, ',')) {
        break;
      }
    }
    if (status.isOk()) {
      status = requireKeyword(sql, "WHERE");
    }
    return status.isOk() ? predicates(sql, result, false) : status;
  }

  private StatusCode appendUpdate(CharSequence sql, SqlCommand result) {
    StatusCode status = columnIdentifier(sql, result);
    if (status.isOk()) status = requireCharacter(sql, '=');
    return status.isOk() ? appendUpdateValue(sql, result) : status;
  }

  private StatusCode appendUpdateValue(CharSequence sql, SqlCommand result) {
    return updateValues.parse(sql, result);
  }

  private StatusCode parseDelete(CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "FROM");
    if (status.isOk()) {
      status = identifier(sql, result.writableTableName());
    }
    if (status.isOk()) {
      status = requireKeyword(sql, "WHERE");
    }
    return status.isOk() ? predicates(sql, result, false) : status;
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

  private StatusCode predicates(
      CharSequence sql,
      SqlCommand result,
      boolean qualified) {
    boolean disjunction = false;
    while (true) {
      StatusCode status = parsePredicate(sql, result, qualified, disjunction);
      if (!status.isOk()) {
        return status;
      }
      if (consumeKeyword(sql, "AND")) {
        disjunction = false;
      } else if (consumeKeyword(sql, "OR")) {
        disjunction = true;
      } else {
        return StatusCode.OK;
      }
    }
  }

  private StatusCode parsePredicate(
      CharSequence sql,
      SqlCommand result,
      boolean qualified,
      boolean disjunction) {
    predicateResult.reset();
    SqlIdentifier table = result.writableNextPredicateTableName();
    SqlIdentifier column = result.writableNextPredicateColumnName();
    if (table == null || column == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = parsePredicateReference(sql, table, column, qualified);
    if (status.isOk()) {
      status = parsePredicateOperator(sql);
    }
    if (status.isOk()) {
      status = parsePredicateValue(sql, result, table, column);
    }
    if (status.isOk()) {
      status = appendPredicate(result, disjunction);
    }
    return status;
  }

  private StatusCode parsePredicateReference(
      CharSequence sql,
      SqlIdentifier table,
      SqlIdentifier column,
      boolean qualified) {
    identifierScratch.reset();
    StatusCode status = identifier(sql, identifierScratch);
    predicateResult.qualified = status.isOk() && consumeCharacter(sql, '.');
    if (status.isOk() && qualified && !predicateResult.qualified) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk()) {
      return status;
    }
    if (!predicateResult.qualified) {
      column.copyFrom(identifierScratch);
      return StatusCode.OK;
    }
    table.copyFrom(identifierScratch);
    return identifier(sql, column);
  }

  private StatusCode parsePredicateOperator(CharSequence sql) {
    if (consumeKeyword(sql, "IS")) {
      return parseIsPredicate(sql);
    }
    predicateResult.between = consumeKeyword(sql, "BETWEEN");
    predicateResult.comparison = predicateResult.between
        ? SqlComparison.HALF_OPEN_RANGE : comparisonOperator(sql);
    return predicateResult.comparison == null
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }

  private StatusCode parseIsPredicate(CharSequence sql) {
    boolean negated = consumeKeyword(sql, "NOT");
    if (consumeKeyword(sql, "NULL") || consumeKeyword(sql, "UNKNOWN")) {
      predicateResult.nullPredicate = true;
      predicateResult.nullNegated = negated;
      return StatusCode.OK;
    }
    if (negated) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (consumeKeyword(sql, "TRUE")) {
      predicateResult.value = 1;
    } else if (consumeKeyword(sql, "FALSE")) {
      predicateResult.value = 0;
    } else {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    predicateResult.comparison = SqlComparison.EQUAL;
    predicateResult.typeDescriptor = SqlTypeDescriptor.BOOLEAN;
    predicateResult.truthPredicate = true;
    return StatusCode.OK;
  }

  private StatusCode parsePredicateValue(
      CharSequence sql,
      SqlCommand result,
      SqlIdentifier table,
      SqlIdentifier column) {
    if (predicateResult.nullPredicate) {
      return StatusCode.OK;
    }
    if (predicateResult.truthPredicate) {
      return StatusCode.OK;
    }
    if (predicateResult.between) {
      return parseBetweenPredicate(sql);
    }
    if (predicateResult.comparison == SqlComparison.IN
        || predicateResult.comparison == SqlComparison.NOT_IN) {
      return parseMembershipPredicate(sql);
    }
    if (predicateResult.comparison == SqlComparison.EQUAL) {
      return parseEqualityPredicate(sql, result);
    }
    return parseLiteralPredicate(sql, table, column);
  }

  private StatusCode parseBetweenPredicate(CharSequence sql) {
    StatusCode status = literal(sql, numberResult);
    if (!status.isOk()) {
      return status;
    }
    predicateResult.lower = numberResult.value;
    predicateResult.varchar = numberResult.varchar;
    predicateResult.typeDescriptor = numberResult.typeDescriptor;
    status = requireKeyword(sql, "AND");
    if (status.isOk()) {
      status = literal(sql, numberResult);
    }
    if (!status.isOk()) {
      return status;
    }
    predicateResult.upper = numberResult.value;
    predicateResult.textScalars = numberResult.textScalars;
    if (predicateResult.typeDescriptor != numberResult.typeDescriptor) {
      int common = commonLiteralDescriptor(
          predicateResult.typeDescriptor, numberResult.typeDescriptor);
      if (common == 0
          || !normalizePredicateRange(common, numberResult.typeDescriptor)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    if (predicateResult.lower > predicateResult.upper) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (predicateResult.upper == Long.MAX_VALUE) {
      predicateResult.comparison = SqlComparison.GREATER_OR_EQUAL;
      predicateResult.value = predicateResult.lower;
    } else {
      predicateResult.upper++;
    }
    return StatusCode.OK;
  }

  private StatusCode parseMembershipPredicate(CharSequence sql) {
    StatusCode status = requireCharacter(sql, '(');
    boolean complete = false;
    boolean typeSet = false;
    while (status.isOk() && !complete) {
      if (consumeKeyword(sql, "NULL")) {
        predicateResult.membershipHasNull = true;
      } else if (predicateResult.membershipCount >= literalMembershipValues.length) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      } else {
        status = appendMembershipLiteral(sql, typeSet);
        typeSet = status.isOk();
      }
      if (status.isOk()) {
        complete = consumeCharacter(sql, ')');
        if (!complete) {
          status = requireCharacter(sql, ',');
        }
      }
    }
    return status;
  }

  private StatusCode appendMembershipLiteral(CharSequence sql, boolean typeSet) {
    StatusCode status = literal(sql, numberResult);
    if (!status.isOk()) return status;
    if (typeSet && predicateResult.typeDescriptor != numberResult.typeDescriptor) {
      int common = commonLiteralDescriptor(
          predicateResult.typeDescriptor, numberResult.typeDescriptor);
      if (common == 0 || !normalizeMembership(common, numberResult.typeDescriptor)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    predicateResult.varchar = numberResult.varchar;
    predicateResult.textScalars = numberResult.textScalars;
    predicateResult.typeDescriptor = numberResult.typeDescriptor;
    literalMembershipValues[predicateResult.membershipCount++] = numberResult.value;
    return StatusCode.OK;
  }

  private boolean normalizePredicateRange(int target, int upperDescriptor) {
    if (SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      predicateResult.typeDescriptor = target;
      numberResult.typeDescriptor = target;
      return true;
    }
    if (!ExactDecimal.widenScale(
        predicateResult.lower,
        predicateResult.typeDescriptor,
        target,
        decimalResult)) {
      return false;
    }
    predicateResult.lower = decimalResult.value;
    if (!ExactDecimal.widenScale(
        predicateResult.upper,
        upperDescriptor,
        target,
        decimalResult)) {
      return false;
    }
    predicateResult.upper = decimalResult.value;
    predicateResult.typeDescriptor = target;
    return true;
  }

  private boolean normalizeMembership(int target, int candidateDescriptor) {
    if (SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      predicateResult.typeDescriptor = target;
      numberResult.typeDescriptor = target;
      return true;
    }
    for (int index = 0; index < predicateResult.membershipCount; index++) {
      if (!ExactDecimal.widenScale(
          literalMembershipValues[index],
          predicateResult.typeDescriptor,
          target,
          decimalResult)) {
        return false;
      }
      literalMembershipValues[index] = decimalResult.value;
    }
    if (!ExactDecimal.widenScale(
        numberResult.value,
        candidateDescriptor,
        target,
        decimalResult)) {
      return false;
    }
    numberResult.value = decimalResult.value;
    numberResult.typeDescriptor = target;
    predicateResult.typeDescriptor = target;
    return true;
  }

  private static int commonLiteralDescriptor(int left, int right) {
    if (SqlTypeDescriptor.typeId(left) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && SqlTypeDescriptor.typeId(right) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return SqlTypeDescriptor.varchar(Math.max(
          SqlTypeDescriptor.parameterOne(left), SqlTypeDescriptor.parameterOne(right)));
    }
    if (SqlTypeDescriptor.typeId(left) != SqlTypeDescriptor.TYPE_ID_DECIMAL
        || SqlTypeDescriptor.typeId(right) != SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      return 0;
    }
    int scale = Math.max(
        SqlTypeDescriptor.parameterTwo(left), SqlTypeDescriptor.parameterTwo(right));
    int integerDigits = Math.max(
        SqlTypeDescriptor.parameterOne(left) - SqlTypeDescriptor.parameterTwo(left),
        SqlTypeDescriptor.parameterOne(right) - SqlTypeDescriptor.parameterTwo(right));
    return SqlTypeDescriptor.decimal(integerDigits + scale, scale);
  }

  private StatusCode parseEqualityPredicate(CharSequence sql, SqlCommand result) {
    skipSpaces(sql);
    if (input.position() == syntheticPredicateOffset) {
      syntheticPredicateIndex = result.predicateCount();
      StatusCode status = number(sql, numberResult);
      predicateResult.value = numberResult.value;
      return status;
    }
    if (input.startsLiteral(sql)) {
      return parsePredicateLiteral(sql);
    }
    return parsePredicateColumn(sql, result);
  }

  private StatusCode parsePredicateColumn(CharSequence sql, SqlCommand result) {
    SqlIdentifier table = result.writableNextPredicateValueTableName();
    SqlIdentifier column = result.writableNextPredicateValueColumnName();
    if (table == null || column == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    identifierScratch.reset();
    StatusCode status = identifier(sql, identifierScratch);
    boolean qualified = status.isOk() && consumeCharacter(sql, '.');
    if (!status.isOk()) {
      return status;
    }
    if (qualified) {
      table.copyFrom(identifierScratch);
      status = identifier(sql, column);
    } else {
      column.copyFrom(identifierScratch);
    }
    predicateResult.columnEquality = status.isOk();
    return status;
  }

  private StatusCode parseLiteralPredicate(
      CharSequence sql,
      SqlIdentifier table,
      SqlIdentifier column) {
    StatusCode status = parsePredicateLiteral(sql);
    if (status.isOk()
        && predicateResult.comparison == SqlComparison.GREATER_OR_EQUAL
        && predicateResult.typeDescriptor == SqlTypeDescriptor.BIGINT
        && consumeHalfOpenUpper(sql, table, column, predicateResult.qualified)) {
      predicateResult.lower = predicateResult.value;
      predicateResult.upper = numberResult.value;
      predicateResult.comparison = SqlComparison.HALF_OPEN_RANGE;
    }
    return status;
  }

  private StatusCode parsePredicateLiteral(CharSequence sql) {
    StatusCode status = literal(sql, numberResult);
    if (status.isOk()) {
      predicateResult.value = numberResult.value;
      predicateResult.varchar = numberResult.varchar;
      predicateResult.textScalars = numberResult.textScalars;
      predicateResult.typeDescriptor = numberResult.typeDescriptor;
    }
    return status;
  }

  private StatusCode appendPredicate(SqlCommand result, boolean disjunction) {
    StatusCode status = StatusCode.OK;
    if (predicateResult.nullPredicate) {
      result.appendNullPredicate(predicateResult.nullNegated);
    } else if (predicateResult.comparison == SqlComparison.IN
        || predicateResult.comparison == SqlComparison.NOT_IN) {
      status = result.appendLiteralMembership(
          literalMembershipValues,
          predicateResult.membershipCount,
          predicateResult.membershipHasNull,
          predicateResult.comparison == SqlComparison.NOT_IN,
          predicateResult.typeDescriptor);
    } else if (predicateResult.columnEquality) {
      result.appendColumnPredicate();
    } else if (predicateResult.comparison == SqlComparison.HALF_OPEN_RANGE) {
      result.appendPredicate(
          0, predicateResult.lower, predicateResult.upper, false);
    } else {
      result.appendComparison(predicateResult.value, predicateResult.comparison);
    }
    if (status.isOk()
        && !predicateResult.columnEquality
        && !predicateResult.nullPredicate) {
      result.markLastPredicateType(predicateResult.typeDescriptor);
    }
    if (status.isOk() && disjunction) {
      result.markLastPredicateDisjunction();
    }
    return status;
  }

  private SqlComparison comparisonOperator(CharSequence sql) {
    if (consumeKeyword(sql, "NOT")) {
      return consumeKeyword(sql, "IN") ? SqlComparison.NOT_IN : null;
    }
    if (consumeKeyword(sql, "IN")) {
      return SqlComparison.IN;
    }
    if (consumeCharacter(sql, '=')) {
      return SqlComparison.EQUAL;
    }
    if (consumeCharacter(sql, '!')) {
      return consumeCharacter(sql, '=') ? SqlComparison.NOT_EQUAL : null;
    }
    if (consumeCharacter(sql, '<')) return lessComparison(sql);
    if (consumeCharacter(sql, '>')) return greaterComparison(sql);
    return null;
  }

  private SqlComparison lessComparison(CharSequence sql) {
    if (consumeCharacter(sql, '>')) return SqlComparison.NOT_EQUAL;
    return consumeCharacter(sql, '=')
        ? SqlComparison.LESS_OR_EQUAL : SqlComparison.LESS_THAN;
  }

  private SqlComparison greaterComparison(CharSequence sql) {
    return consumeCharacter(sql, '=')
        ? SqlComparison.GREATER_OR_EQUAL : SqlComparison.GREATER_THAN;
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
    SqlComparison comparison = comparisonOperator(sql);
    if (comparison == null
        || comparison == SqlComparison.HALF_OPEN_RANGE
        || comparison == SqlComparison.IN
        || comparison == SqlComparison.NOT_IN) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    status = literal(sql, numberResult);
    if (!status.isOk()) {
      return status;
    }
    status = coerceLiteral(result.columnTypeDescriptor(column));
    if (!status.isOk()) {
      return status;
    }
    status = requireCharacter(sql, ')');
    if (!status.isOk()) {
      return status;
    }
    result.markLastColumnCheck(comparison, numberResult.value);
    return StatusCode.OK;
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

  private boolean consumeHalfOpenUpper(
      CharSequence sql,
      CharSequence table,
      CharSequence column,
      boolean qualified) {
    int start = input.position();
    if (!consumeKeyword(sql, "AND")) {
      return false;
    }
    StatusCode status = StatusCode.OK;
    if (qualified) {
      status = matchingIdentifier(sql, table);
      if (status.isOk()) {
        status = requireCharacter(sql, '.');
      }
    }
    if (status.isOk()) {
      status = matchingIdentifier(sql, column);
    }
    if (status.isOk()) {
      status = requireCharacter(sql, '<');
    }
    if (status.isOk() && nextCharacter(sql, '=')) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      status = number(sql, numberResult);
    }
    if (status.isOk()) {
      return true;
    }
    input.position(start);
    return false;
  }

  private boolean nextCharacter(CharSequence sql, char expected) {
    int start = input.position();
    boolean matches = consumeCharacter(sql, expected);
    input.position(start);
    return matches;
  }

  private StatusCode row(CharSequence sql, LongRow result) {
    resetRow(result);
    StatusCode status = requireCharacter(sql, '(');
    while (status.isOk()) {
      if (result.count >= SqlCommand.MAXIMUM_COLUMNS) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = appendRowValue(sql, result);
      if (!status.isOk() || consumeCharacter(sql, ')')) {
        break;
      }
      status = requireCharacter(sql, ',');
    }
    if (!status.isOk()) {
      return status;
    }
    return result.count >= 1 ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static void resetRow(LongRow result) {
    result.count = 0;
    result.nullMask = 0;
    result.defaultMask = 0;
    for (int index = 0; index < result.typeDescriptors.length; index++) {
      result.typeDescriptors[index] = 0;
    }
  }

  private StatusCode appendRowValue(CharSequence sql, LongRow result) {
    boolean nullValue = consumeKeyword(sql, "NULL");
    boolean defaultValue = !nullValue && consumeKeyword(sql, "DEFAULT");
    StatusCode status = parseRowLiteral(sql, nullValue, defaultValue);
    if (!status.isOk()) {
      return status;
    }
    int index = result.count++;
    result.values[index] = nullValue ? 0 : numberResult.value;
    if (nullValue) {
      result.nullMask |= 1L << index;
    } else if (defaultValue) {
      result.defaultMask |= 1L << index;
    } else {
      result.typeDescriptors[index] = numberResult.typeDescriptor;
    }
    return StatusCode.OK;
  }

  private StatusCode parseRowLiteral(
      CharSequence sql,
      boolean nullValue,
      boolean defaultValue) {
    if (nullValue || defaultValue) {
      return StatusCode.OK;
    }
    return literal(sql, numberResult);
  }

  private StatusCode columnIdentifier(CharSequence sql, SqlCommand result) {
    SqlIdentifier column = result.writableNextColumnName();
    return column == null ? StatusCode.RESOURCE_EXHAUSTED : identifier(sql, column);
  }

  private StatusCode selectColumnIdentifier(CharSequence sql, SqlCommand result) {
    int columnIndex = result.columnCount();
    if (consumeKeyword(sql, "NULL")) {
      SqlIdentifier column = result.writableNextColumnName();
      if (column == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      setIdentifier(column, "null");
      result.markLastProjectionNull();
      return optionalColumnAlias(sql, result, columnIndex);
    }
    identifierScratch.reset();
    StatusCode status = identifier(sql, identifierScratch);
    SqlIdentifier column = status.isOk() ? result.writableNextColumnName() : null;
    if (!status.isOk()) {
      return status;
    }
    if (column == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (consumeCharacter(sql, '.')) {
      result.writableColumnTableName(result.columnCount() - 1).copyFrom(identifierScratch);
      status = identifier(sql, column);
    } else {
      column.copyFrom(identifierScratch);
    }
    return status.isOk()
        ? optionalColumnAlias(sql, result, columnIndex) : status;
  }

  private StatusCode valueAggregate(CharSequence sql, SqlCommand result) {
    StatusCode status = requireCharacter(sql, '(');
    if (status.isOk()) {
      status = aggregateColumn(sql, result);
    }
    return status.isOk() ? aggregateSource(sql, result) : status;
  }

  private StatusCode aggregateColumn(CharSequence sql, SqlCommand result) {
    StatusCode status = selectColumnIdentifier(sql, result);
    if (status.isOk()
        && (result.isNullProjection(0)
            || result.columnAlias(0).length() > 0)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    return status.isOk() ? optionalColumnAlias(sql, result, 0) : status;
  }

  private StatusCode groupAggregateColumn(CharSequence sql, SqlCommand result) {
    int columnIndex = result.columnCount();
    StatusCode status = selectColumnIdentifier(sql, result);
    if (status.isOk()
        && (result.isNullProjection(columnIndex)
            || result.columnAlias(columnIndex).length() > 0)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    return status.isOk()
        ? optionalColumnAlias(sql, result, columnIndex) : status;
  }

  private static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  private StatusCode groupHaving(
      CharSequence sql,
      SqlCommand result,
      SqlCommandType type) {
    String function = type == SqlCommandType.GROUP_SUM
        ? "SUM" : type == SqlCommandType.GROUP_AVG
            ? "AVG" : type == SqlCommandType.GROUP_MIN
            ? "MIN" : type == SqlCommandType.GROUP_MAX ? "MAX" : "COUNT";
    StatusCode status = requireKeyword(sql, function);
    if (status.isOk()) {
      status = requireCharacter(sql, '(');
    }
    if (status.isOk()) status = groupHavingOperand(sql, result, type);
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    return status.isOk() ? parseGroupHavingComparison(sql, result) : status;
  }

  private StatusCode parseGroupHavingComparison(
      CharSequence sql, SqlCommand result) {
    SqlComparison comparison = comparisonOperator(sql);
    if (comparison == null
        || comparison == SqlComparison.HALF_OPEN_RANGE
        || comparison == SqlComparison.IN
        || comparison == SqlComparison.NOT_IN) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = literal(sql, numberResult);
    if (status.isOk()) {
      result.setGroupHaving(
          comparison, numberResult.value, numberResult.typeDescriptor);
    }
    return status;
  }

  private StatusCode groupHavingOperand(
      CharSequence sql, SqlCommand result, SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        ? requireCharacter(sql, '*')
        : matchingIdentifier(sql, result.secondColumnName());
  }

  private StatusCode aggregateSource(CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "FROM");
    if (status.isOk()) {
      status = identifier(sql, result.writableTableName());
    }
    if (status.isOk()) {
      status = optionalTableAlias(sql, result);
    }
    if (status.isOk() && consumeKeyword(sql, "WHERE")) {
      status = predicates(sql, result, false);
    }
    return status;
  }

  private StatusCode optionalColumnAlias(
      CharSequence sql,
      SqlCommand result,
      int columnIndex) {
    if (consumeKeyword(sql, "AS")) {
      return identifier(sql, result.writableColumnAlias(columnIndex));
    }
    skipSpaces(sql);
    int position = input.position();
    if (position >= sql.length()
        || sql.charAt(position) == ','
        || sql.charAt(position) == ';'
        || sql.charAt(position) == ')'
        || nextKeyword(sql, "FROM")
        || nextKeyword(sql, "JOIN")
        || nextKeyword(sql, "WHERE")
        || nextKeyword(sql, "GROUP")
        || nextKeyword(sql, "ORDER")
        || nextKeyword(sql, "LIMIT")) {
      return StatusCode.OK;
    }
    return identifierStart(sql.charAt(input.position()))
        ? identifier(sql, result.writableColumnAlias(columnIndex))
        : StatusCode.OK;
  }

  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    return input.identifier(sql, result);
  }

  private StatusCode optionalTableAlias(
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

  private StatusCode optionalJoinTableAlias(
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

  private StatusCode matchingIdentifier(CharSequence sql, CharSequence expected) {
    SqlIdentifier actual = rowResult.identifier;
    actual.reset();
    StatusCode status = identifier(sql, actual);
    if (!status.isOk()) {
      return status;
    }
    return sameIdentifier(actual, expected)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode matchingEitherIdentifier(
      CharSequence sql,
      CharSequence first,
      CharSequence second) {
    SqlIdentifier actual = rowResult.identifier;
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

  private static final class PredicateResult {
    private SqlComparison comparison;
    private long value;
    private long lower;
    private long upper;
    private int membershipCount;
    private int textScalars;
    private int typeDescriptor;
    private boolean qualified;
    private boolean nullPredicate;
    private boolean nullNegated;
    private boolean between;
    private boolean membershipHasNull;
    private boolean columnEquality;
    private boolean varchar;
    private boolean truthPredicate;

    private void reset() {
      comparison = null;
      value = 0;
      lower = 0;
      upper = 0;
      membershipCount = 0;
      textScalars = 0;
      typeDescriptor = 0;
      qualified = false;
      nullPredicate = false;
      nullNegated = false;
      between = false;
      membershipHasNull = false;
      columnEquality = false;
      varchar = false;
      truthPredicate = false;
    }
  }

  private static final class LongRow {
    private final long[] values = new long[SqlCommand.MAXIMUM_COLUMNS];
    private final int[] typeDescriptors = new int[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlIdentifier identifier = new SqlIdentifier();
    private int count;
    private long nullMask;
    private long defaultMask;
  }
}
