package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Allocation-free parser for River's first executable SQL point-statement subset. */
public final class SqlParser {
  private final LongResult numberResult = new LongResult();
  private final LongRow rowResult = new LongRow();
  private final SqlIdentifier identifierScratch = new SqlIdentifier();
  private final SqlSourceView sourceView = new SqlSourceView();
  private final SqlScalarSourceView scalarSourceView = new SqlScalarSourceView();
  private int offset;
  private int scalarPredicateIndex = -1;
  private int existenceWhereStart = -1;
  private boolean existenceNegated;
  private int membershipOperatorStart = -1;
  private boolean membershipNegated;

  public StatusCode parse(String sql, SqlCommand result) {
    return parseText(sql, result);
  }

  public StatusCode parseQuery(
      String sql,
      SqlQuery query,
      SqlCommand result) {
    if (sql == null || query == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    query.reset();
    int derived = findDerivedSource(sql, 0, sql.length());
    if (derived >= 0) {
      StatusCode status = parseDerivedBlocks(sql, 0, sql.length(), query);
      return status.isOk() ? query.compileDerived(result) : status;
    }
    int exists = findExistenceSource(sql, 0, sql.length());
    if (exists >= 0) {
      return parseExistencePredicate(sql, exists, query, result);
    }
    int membership = findMembershipSource(sql, 0, sql.length());
    if (membership >= 0) {
      return parseMembershipPredicate(sql, membership, query, result);
    }
    int scalar = findScalarSource(sql, 0, sql.length());
    return scalar < 0
        ? parseText(sql, result)
        : parseScalarPredicate(sql, scalar, query, result);
  }

  private StatusCode parseExistencePredicate(
      String sql,
      int open,
      SqlQuery query,
      SqlCommand result) {
    StatusCode status = matchingCloseParenthesis(sql, open, sql.length()) < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : parseExistenceBlocks(sql, 0, sql.length(), query);
    return status.isOk()
        ? query.compileExistencePredicate(result, query.existenceNegated()) : status;
  }

  private StatusCode parseExistenceBlocks(
      String sql,
      int start,
      int end,
      SqlQuery query) {
    int open = findExistenceSource(sql, start, end);
    int close = open < 0 ? -1 : matchingCloseParenthesis(sql, open, end);
    int whereStart = existenceWhereStart;
    boolean negated = existenceNegated;
    if (open < 0 || close < 0 || whereStart < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int parentIndex = query.blockCount();
    SqlCommand parent = query.nextBlock();
    if (parent == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    sourceView.set(sql, start, whereStart, close + 1, end);
    StatusCode status = parseText(sourceView, parent);
    if (status.isOk()) {
      query.setExistencePredicate(parentIndex, negated);
      status = parseNestedBlocks(sql, open + 1, close, query);
    }
    return status;
  }

  private StatusCode parseScalarPredicate(
      String sql,
      int open,
      SqlQuery query,
      SqlCommand result) {
    StatusCode status = matchingCloseParenthesis(sql, open, sql.length()) < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : parseScalarBlocks(sql, 0, sql.length(), query);
    return status.isOk()
        ? query.compileScalarPredicate(result, query.scalarPredicate()) : status;
  }

  private StatusCode parseScalarBlocks(
      String sql,
      int start,
      int end,
      SqlQuery query) {
    int open = findScalarSource(sql, start, end);
    int close = open < 0 ? -1 : matchingCloseParenthesis(sql, open, end);
    if (open < 0 || close < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int parentIndex = query.blockCount();
    SqlCommand parent = query.nextBlock();
    if (parent == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    scalarSourceView.set(sql, start, open, close + 1, end, false);
    scalarPredicateIndex = -1;
    StatusCode status = parseText(scalarSourceView, parent);
    if (status.isOk() && scalarPredicateIndex < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      query.setScalarPredicate(parentIndex, scalarPredicateIndex);
      status = parseNestedBlocks(sql, open + 1, close, query);
    }
    return status;
  }

  private StatusCode parseMembershipPredicate(
      String sql,
      int open,
      SqlQuery query,
      SqlCommand result) {
    StatusCode status = matchingCloseParenthesis(sql, open, sql.length()) < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : parseMembershipBlocks(sql, 0, sql.length(), query);
    return status.isOk()
        ? query.compileMembershipPredicate(
            result, query.membershipPredicate(), query.membershipNegated())
        : status;
  }

  private StatusCode parseMembershipBlocks(
      String sql,
      int start,
      int end,
      SqlQuery query) {
    int open = findMembershipSource(sql, start, end);
    int close = open < 0 ? -1 : matchingCloseParenthesis(sql, open, end);
    int operatorStart = membershipOperatorStart;
    boolean negated = membershipNegated;
    if (open < 0 || close < 0 || operatorStart < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int parentIndex = query.blockCount();
    SqlCommand parent = query.nextBlock();
    if (parent == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    scalarSourceView.set(sql, start, operatorStart, close + 1, end, true);
    scalarPredicateIndex = -1;
    StatusCode status = parseText(scalarSourceView, parent);
    if (status.isOk() && scalarPredicateIndex < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      query.setMembershipPredicate(parentIndex, scalarPredicateIndex, negated);
      status = parseNestedBlocks(sql, open + 1, close, query);
    }
    return status;
  }

  private StatusCode parseNestedBlocks(
      String sql,
      int start,
      int end,
      SqlQuery query) {
    if (findExistenceSource(sql, start, end) >= 0) {
      return parseExistenceBlocks(sql, start, end, query);
    }
    if (findMembershipSource(sql, start, end) >= 0) {
      return parseMembershipBlocks(sql, start, end, query);
    }
    if (findScalarSource(sql, start, end) >= 0) {
      return parseScalarBlocks(sql, start, end, query);
    }
    SqlCommand nested = query.nextBlock();
    if (nested == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    sourceView.set(sql, start, end, end, end);
    return parseText(sourceView, nested);
  }

  private StatusCode parseDerivedBlocks(
      String sql,
      int start,
      int end,
      SqlQuery query) {
    SqlCommand block = query.nextBlock();
    if (block == null) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    int open = findDerivedSource(sql, start, end);
    if (open < 0) {
      sourceView.set(sql, start, end, end, end);
      return parseText(sourceView, block);
    }
    int close = matchingCloseParenthesis(sql, open, end);
    if (close < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    sourceView.set(sql, start, open, close + 1, end);
    StatusCode status = parseText(sourceView, block);
    return status.isOk()
        ? parseDerivedBlocks(sql, open + 1, close, query) : status;
  }

  private static int findDerivedSource(String sql, int start, int end) {
    int depth = 0;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')') {
        if (depth <= 0) {
          return -1;
        }
        depth--;
      } else if (depth == 0
          && matchesKeyword(sql, index, end, "FROM")) {
        int source = index + 4;
        while (source < end && Character.isWhitespace(sql.charAt(source))) {
          source++;
        }
        return source < end && sql.charAt(source) == '(' ? source : -1;
      }
    }
    return -1;
  }

  private static int matchingCloseParenthesis(String sql, int open, int end) {
    int depth = 0;
    for (int index = open; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  private static int findScalarSource(String sql, int start, int end) {
    int depth = 0;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')') {
        if (depth <= 0) {
          return -1;
        }
        depth--;
      } else if (depth == 0 && character == '=') {
        int open = index + 1;
        while (open < end && Character.isWhitespace(sql.charAt(open))) {
          open++;
        }
        if (open >= end || sql.charAt(open) != '(') {
          continue;
        }
        int select = open + 1;
        while (select < end && Character.isWhitespace(sql.charAt(select))) {
          select++;
        }
        if (matchesKeyword(sql, select, end, "SELECT")) {
          return open;
        }
      }
    }
    return -1;
  }

  private int findMembershipSource(String sql, int start, int end) {
    membershipOperatorStart = -1;
    membershipNegated = false;
    int depth = 0;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')') {
        if (depth <= 0) {
          return -1;
        }
        depth--;
      } else if (depth == 0 && matchesKeyword(sql, index, end, "IN")) {
        int open = index + 2;
        while (open < end && Character.isWhitespace(sql.charAt(open))) {
          open++;
        }
        if (open >= end || sql.charAt(open) != '(') {
          continue;
        }
        int select = open + 1;
        while (select < end && Character.isWhitespace(sql.charAt(select))) {
          select++;
        }
        if (!matchesKeyword(sql, select, end, "SELECT")) {
          continue;
        }
        int operator = index;
        int priorEnd = index;
        while (priorEnd > start && Character.isWhitespace(sql.charAt(priorEnd - 1))) {
          priorEnd--;
        }
        int priorStart = priorEnd - 3;
        if (priorStart >= start
            && matchesKeyword(sql, priorStart, priorEnd, "NOT")) {
          membershipNegated = true;
          operator = priorStart;
        }
        membershipOperatorStart = operator;
        return open;
      }
    }
    return -1;
  }

  private int findExistenceSource(String sql, int start, int end) {
    existenceWhereStart = -1;
    existenceNegated = false;
    int depth = 0;
    for (int index = start; index < end; index++) {
      char character = sql.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')') {
        if (depth <= 0) {
          return -1;
        }
        depth--;
      } else if (depth == 0 && matchesKeyword(sql, index, end, "WHERE")) {
        int predicate = index + 5;
        while (predicate < end && Character.isWhitespace(sql.charAt(predicate))) {
          predicate++;
        }
        if (matchesKeyword(sql, predicate, end, "NOT")) {
          existenceNegated = true;
          predicate += 3;
          while (predicate < end && Character.isWhitespace(sql.charAt(predicate))) {
            predicate++;
          }
        }
        if (!matchesKeyword(sql, predicate, end, "EXISTS")) {
          return -1;
        }
        int open = predicate + 6;
        while (open < end && Character.isWhitespace(sql.charAt(open))) {
          open++;
        }
        if (open >= end || sql.charAt(open) != '(') {
          return -1;
        }
        int select = open + 1;
        while (select < end && Character.isWhitespace(sql.charAt(select))) {
          select++;
        }
        if (matchesKeyword(sql, select, end, "SELECT")) {
          existenceWhereStart = index;
          return open;
        }
        return -1;
      }
    }
    return -1;
  }

  private static boolean matchesKeyword(
      String sql,
      int start,
      int end,
      String keyword) {
    if (start > 0 && identifierPart(sql.charAt(start - 1))
        || end - start < keyword.length()) {
      return false;
    }
    for (int index = 0; index < keyword.length(); index++) {
      if (upper(sql.charAt(start + index)) != keyword.charAt(index)) {
        return false;
      }
    }
    int keywordEnd = start + keyword.length();
    return keywordEnd >= end || !identifierPart(sql.charAt(keywordEnd));
  }

  private StatusCode parseText(CharSequence sql, SqlCommand result) {
    if (sql == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    offset = 0;
    skipSpaces(sql);
    StatusCode status;
    SqlCommandType type;
    long key = 0;
    long value = 0;
    long scanLower = 0;
    long scanUpper = 0;
    boolean boundedScan = false;
    boolean readCommittedTransaction = false;
    boolean serializableTransaction = false;
    if (consumeKeyword(sql, "BEGIN")) {
      type = SqlCommandType.BEGIN;
      status = StatusCode.OK;
      if (consumeKeyword(sql, "SERIALIZABLE")) {
        serializableTransaction = true;
      } else if (consumeKeyword(sql, "READ")) {
        status = requireKeyword(sql, "COMMITTED");
        readCommittedTransaction = status.isOk();
      } else if (consumeKeyword(sql, "REPEATABLE")) {
        status = requireKeyword(sql, "READ");
      }
    } else if (consumeKeyword(sql, "SAVEPOINT")) {
      type = SqlCommandType.SAVEPOINT;
      status = identifier(sql, result.writableSavepointName());
    } else if (consumeKeyword(sql, "COMMIT")) {
      type = SqlCommandType.COMMIT;
      status = StatusCode.OK;
    } else if (consumeKeyword(sql, "ROLLBACK")) {
      if (consumeKeyword(sql, "TO")) {
        type = SqlCommandType.ROLLBACK_TO_SAVEPOINT;
        consumeKeyword(sql, "SAVEPOINT");
        status = identifier(sql, result.writableSavepointName());
      } else {
        type = SqlCommandType.ROLLBACK;
        status = StatusCode.OK;
      }
    } else if (consumeKeyword(sql, "RELEASE")) {
      type = SqlCommandType.RELEASE_SAVEPOINT;
      status = requireKeyword(sql, "SAVEPOINT");
      if (status.isOk()) {
        status = identifier(sql, result.writableSavepointName());
      }
    } else if (consumeKeyword(sql, "CHECKPOINT")) {
      type = SqlCommandType.CHECKPOINT;
      status = StatusCode.OK;
    } else if (consumeKeyword(sql, "CREATE")) {
      if (consumeKeyword(sql, "TABLE")) {
        type = SqlCommandType.CREATE_TABLE;
        status = identifier(sql, result.writableTableName());
        if (status.isOk() && consumeCharacter(sql, '(')) {
          status = columnIdentifier(sql, result);
          if (status.isOk()) {
            status = requireKeyword(sql, "BIGINT");
          }
          if (status.isOk()) {
            status = requireKeyword(sql, "PRIMARY");
          }
          if (status.isOk()) {
            status = requireKeyword(sql, "KEY");
          }
          while (status.isOk() && !consumeCharacter(sql, ')')) {
            status = requireCharacter(sql, ',');
            if (status.isOk()) {
              status = columnIdentifier(sql, result);
            }
            if (status.isOk()) {
              status = requireKeyword(sql, "BIGINT");
            }
          }
          if (status.isOk() && result.columnCount() < 2) {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          }
        } else if (status.isOk()) {
          setIdentifier(result.writableNextColumnName(), "key");
          setIdentifier(result.writableNextColumnName(), "value");
        }
      } else {
        boolean unique = consumeKeyword(sql, "UNIQUE");
        type = unique ? SqlCommandType.CREATE_UNIQUE_INDEX : SqlCommandType.CREATE_INDEX;
        status = requireKeyword(sql, "INDEX");
        if (status.isOk()) {
          status = identifier(sql, result.writableIndexName());
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "ON");
        }
        if (status.isOk()) {
          status = identifier(sql, result.writableTableName());
        }
        if (status.isOk()) {
          status = requireCharacter(sql, '(');
        }
        if (status.isOk()) {
          status = columnIdentifier(sql, result);
        }
        if (status.isOk()) {
          status = requireCharacter(sql, ')');
        }
      }
    } else if (consumeKeyword(sql, "INSERT")) {
      type = SqlCommandType.INSERT;
      status = requireKeyword(sql, "INTO");
      if (status.isOk()) {
        status = identifier(sql, result.writableTableName());
      }
      if (status.isOk() && consumeCharacter(sql, '(')) {
        while (status.isOk()) {
          status = columnIdentifier(sql, result);
          if (!status.isOk() || consumeCharacter(sql, ')')) {
            break;
          }
          status = requireCharacter(sql, ',');
        }
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "VALUES");
      }
      if (status.isOk()) {
        status = row(sql, rowResult);
        key = rowResult.values[0];
        value = rowResult.values[1];
        if (status.isOk()) {
          result.appendInsert(
              rowResult.values, rowResult.nullMask, rowResult.count);
        }
      }
      while (status.isOk() && consumeCharacter(sql, ',')) {
        if (result.insertRowCount() >= SqlCommand.MAXIMUM_INSERT_ROWS) {
          status = StatusCode.RESOURCE_EXHAUSTED;
        } else {
          status = row(sql, rowResult);
          if (status.isOk() && rowResult.count != result.insertColumnCount()) {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          }
          if (status.isOk()) {
            result.appendInsert(
                rowResult.values, rowResult.nullMask, rowResult.count);
          }
        }
      }
    } else if (consumeKeyword(sql, "SELECT")) {
      if (consumeKeyword(sql, "COUNT")) {
        type = SqlCommandType.COUNT;
        status = requireCharacter(sql, '(');
        if (status.isOk()) {
          status = requireCharacter(sql, '*');
        }
        if (status.isOk()) {
          status = requireCharacter(sql, ')');
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "FROM");
        }
        if (status.isOk()) {
          status = identifier(sql, result.writableTableName());
        }
        if (status.isOk()) {
          status = optionalTableAlias(sql, result);
        }
        if (status.isOk() && consumeKeyword(sql, "WHERE")) {
          status = predicates(sql, result, false);
        }
      } else if (consumeKeyword(sql, "SUM")) {
        type = SqlCommandType.SUM;
        status = valueAggregate(sql, result);
      } else if (consumeKeyword(sql, "MIN")) {
        type = SqlCommandType.MIN;
        status = valueAggregate(sql, result);
      } else if (consumeKeyword(sql, "MAX")) {
        type = SqlCommandType.MAX;
        status = valueAggregate(sql, result);
      } else {
        boolean distinct = consumeKeyword(sql, "DISTINCT");
        type = distinct ? SqlCommandType.DISTINCT_SCAN : SqlCommandType.SCAN;
        if (!distinct && consumeCharacter(sql, '*')) {
          result.setSelectAll();
          status = StatusCode.OK;
        } else {
          status = selectColumnIdentifier(sql, result);
          if (!distinct && status.isOk() && consumeCharacter(sql, ',')) {
            if (consumeKeyword(sql, "COUNT")) {
              type = SqlCommandType.GROUP_COUNT;
              status = requireCharacter(sql, '(');
              if (status.isOk()) {
                status = requireCharacter(sql, '*');
              }
              if (status.isOk()) {
                status = requireCharacter(sql, ')');
              }
            } else {
              status = selectColumnIdentifier(sql, result);
              while (status.isOk() && consumeCharacter(sql, ',')) {
                status = selectColumnIdentifier(sql, result);
              }
            }
          }
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "FROM");
        }
        if (status.isOk()) {
          status = identifier(sql, result.writableTableName());
        }
        if (status.isOk()) {
          status = optionalTableAlias(sql, result);
        }
        if (status.isOk()
            && type != SqlCommandType.GROUP_COUNT
            && consumeKeyword(sql, "JOIN")) {
          type = SqlCommandType.JOIN_SCAN;
          status = identifier(sql, result.writableJoinTableName());
          if (status.isOk()) {
            status = optionalJoinTableAlias(sql, result);
          }
          if (status.isOk()) {
            status = requireKeyword(sql, "ON");
          }
          if (status.isOk()) {
            status = matchingEitherIdentifier(
                sql, result.tableName(), result.tableAlias());
          }
          if (status.isOk()) {
            status = requireCharacter(sql, '.');
          }
          if (status.isOk()) {
            status = identifier(sql, result.writableJoinOuterColumnName());
          }
          if (status.isOk()) {
            status = requireCharacter(sql, '=');
          }
          if (status.isOk()) {
            status = matchingEitherIdentifier(
                sql, result.joinTableName(), result.joinTableAlias());
          }
          if (status.isOk()) {
            status = requireCharacter(sql, '.');
          }
          if (status.isOk()) {
            status = identifier(sql, result.writableJoinInnerColumnName());
          }
        }
        if (status.isOk()
            && type == SqlCommandType.JOIN_SCAN
            && consumeKeyword(sql, "WHERE")) {
          status = predicates(sql, result, true);
        }
        if (status.isOk()
            && type != SqlCommandType.JOIN_SCAN
            && consumeKeyword(sql, "WHERE")) {
          status = predicates(sql, result, false);
        }
        if (status.isOk() && type == SqlCommandType.GROUP_COUNT) {
          status = requireKeyword(sql, "GROUP");
          if (status.isOk()) {
            status = requireKeyword(sql, "BY");
          }
          if (status.isOk()) {
            status = matchingIdentifier(sql, result.firstColumnName());
          }
        } else if (status.isOk()
            && type == SqlCommandType.SCAN
            && result.hasPredicate()) {
          if (status.isOk() && result.isEqualityPredicate()) {
            type = SqlCommandType.SELECT;
          }
        }
        if (status.isOk()
            && type != SqlCommandType.JOIN_SCAN
            && consumeKeyword(sql, "ORDER")) {
          status = requireKeyword(sql, "BY");
          if (status.isOk()) {
            status = type == SqlCommandType.GROUP_COUNT
                    || type == SqlCommandType.DISTINCT_SCAN
                ? matchingEitherIdentifier(
                    sql,
                    result.firstColumnName(),
                    result.columnOutputName(0))
                : identifier(sql, result.writableOrderColumnName());
          }
          if (status.isOk()) {
            if (consumeKeyword(sql, "ASC")) {
              result.setDescendingOrder(false);
            } else if (consumeKeyword(sql, "DESC")) {
              if (type == SqlCommandType.GROUP_COUNT
                  || type == SqlCommandType.DISTINCT_SCAN) {
                status = StatusCode.INVALID_EXTERNAL_INPUT;
              } else {
                result.setDescendingOrder(true);
              }
            }
          }
        }
        if (status.isOk() && consumeKeyword(sql, "LIMIT")) {
          status = number(sql, numberResult);
          if (status.isOk() && numberResult.value < 0) {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          }
          if (status.isOk()) {
            result.setRowLimit(numberResult.value);
          }
        }
      }
    } else if (consumeKeyword(sql, "UPDATE")) {
      type = SqlCommandType.UPDATE;
      status = identifier(sql, result.writableTableName());
      if (status.isOk()) {
        status = requireKeyword(sql, "SET");
      }
      while (status.isOk()) {
        status = columnIdentifier(sql, result);
        if (status.isOk()) {
          status = requireCharacter(sql, '=');
        }
        boolean nullValue = status.isOk() && consumeKeyword(sql, "NULL");
        if (status.isOk() && !nullValue) {
          status = number(sql, numberResult);
        }
        if (status.isOk()) {
          result.appendUpdate(nullValue ? 0 : numberResult.value, nullValue);
          if (result.updateColumnCount() == 1) {
            value = nullValue ? 0 : numberResult.value;
          }
        }
        if (!status.isOk() || !consumeCharacter(sql, ',')) {
          break;
        }
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "WHERE");
      }
      if (status.isOk()) {
        status = predicates(sql, result, false);
      }
    } else if (consumeKeyword(sql, "DELETE")) {
      type = SqlCommandType.DELETE;
      status = requireKeyword(sql, "FROM");
      if (status.isOk()) {
        status = identifier(sql, result.writableTableName());
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "WHERE");
      }
      if (status.isOk()) {
        status = predicates(sql, result, false);
      }
    } else {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk() || !finish(sql)) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    if (type == SqlCommandType.INSERT) {
      result.setInsert();
    } else if (type == SqlCommandType.SCAN) {
      result.setScan(scanLower, scanUpper, boundedScan);
    } else if (type == SqlCommandType.BEGIN) {
      result.setBegin(readCommittedTransaction, serializableTransaction);
    } else {
      result.set(type, key, value);
    }
    return StatusCode.OK;
  }

  private StatusCode predicates(
      CharSequence sql,
      SqlCommand result,
      boolean qualified) {
    StatusCode status = StatusCode.OK;
    while (status.isOk()) {
      SqlIdentifier table = result.writableNextPredicateTableName();
      SqlIdentifier column = result.writableNextPredicateColumnName();
      if (table == null || column == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      identifierScratch.reset();
      if (status.isOk()) {
        status = identifier(sql, identifierScratch);
      }
      boolean predicateQualified = status.isOk() && consumeCharacter(sql, '.');
      if (status.isOk() && qualified && !predicateQualified) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (status.isOk() && predicateQualified) {
        table.copyFrom(identifierScratch);
        status = identifier(sql, column);
      } else if (status.isOk()) {
        column.copyFrom(identifierScratch);
      }
      boolean nullPredicate = status.isOk() && consumeKeyword(sql, "IS");
      boolean nullPredicateNegated = nullPredicate && consumeKeyword(sql, "NOT");
      if (nullPredicate) {
        status = requireKeyword(sql, "NULL");
      }
      SqlComparison comparison = !nullPredicate && status.isOk()
          ? comparisonOperator(sql) : null;
      if (!nullPredicate && status.isOk() && comparison == null) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      long value = 0;
      long lower = 0;
      long upper = 0;
      boolean columnEquality = false;
      if (comparison == SqlComparison.EQUAL) {
        skipSpaces(sql);
        if (sql == scalarSourceView && scalarSourceView.isReplacement(offset)) {
          scalarPredicateIndex = result.predicateCount();
          status = number(sql, numberResult);
          value = numberResult.value;
        } else if (startsNumber(sql)) {
          status = number(sql, numberResult);
          value = numberResult.value;
        } else {
          SqlIdentifier valueTable = result.writableNextPredicateValueTableName();
          SqlIdentifier valueColumn = result.writableNextPredicateValueColumnName();
          if (valueTable == null || valueColumn == null) {
            return StatusCode.RESOURCE_EXHAUSTED;
          }
          identifierScratch.reset();
          status = identifier(sql, identifierScratch);
          boolean valueQualified = status.isOk() && consumeCharacter(sql, '.');
          if (status.isOk() && valueQualified) {
            valueTable.copyFrom(identifierScratch);
            status = identifier(sql, valueColumn);
          } else if (status.isOk()) {
            valueColumn.copyFrom(identifierScratch);
          }
          columnEquality = status.isOk();
        }
      } else if (!nullPredicate && status.isOk()) {
        status = number(sql, numberResult);
        if (status.isOk()) {
          value = numberResult.value;
          if (comparison == SqlComparison.GREATER_OR_EQUAL
              && consumeHalfOpenUpper(
                  sql, table, column, predicateQualified)) {
            lower = value;
            upper = numberResult.value;
            comparison = SqlComparison.HALF_OPEN_RANGE;
          }
        }
      }
      if (status.isOk()) {
        if (nullPredicate) {
          result.appendNullPredicate(nullPredicateNegated);
        } else if (columnEquality) {
          result.appendColumnPredicate();
        } else if (comparison == SqlComparison.HALF_OPEN_RANGE) {
          result.appendPredicate(0, lower, upper, false);
        } else {
          result.appendComparison(value, comparison);
        }
      }
      if (!status.isOk() || !consumeKeyword(sql, "AND")) {
        return status;
      }
    }
    return status;
  }

  private SqlComparison comparisonOperator(CharSequence sql) {
    if (consumeCharacter(sql, '=')) {
      return SqlComparison.EQUAL;
    }
    if (consumeCharacter(sql, '!')) {
      return consumeCharacter(sql, '=') ? SqlComparison.NOT_EQUAL : null;
    }
    if (consumeCharacter(sql, '<')) {
      if (consumeCharacter(sql, '>')) {
        return SqlComparison.NOT_EQUAL;
      }
      return consumeCharacter(sql, '=')
          ? SqlComparison.LESS_OR_EQUAL : SqlComparison.LESS_THAN;
    }
    if (consumeCharacter(sql, '>')) {
      return consumeCharacter(sql, '=')
          ? SqlComparison.GREATER_OR_EQUAL : SqlComparison.GREATER_THAN;
    }
    return null;
  }

  private boolean consumeHalfOpenUpper(
      CharSequence sql,
      CharSequence table,
      CharSequence column,
      boolean qualified) {
    int start = offset;
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
    offset = start;
    return false;
  }

  private boolean nextCharacter(CharSequence sql, char expected) {
    int start = offset;
    boolean matches = consumeCharacter(sql, expected);
    offset = start;
    return matches;
  }

  private StatusCode row(CharSequence sql, LongRow result) {
    result.count = 0;
    result.nullMask = 0;
    StatusCode status = requireCharacter(sql, '(');
    while (status.isOk()) {
      if (result.count >= SqlCommand.MAXIMUM_COLUMNS) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      boolean nullValue = consumeKeyword(sql, "NULL");
      if (!nullValue) {
        status = number(sql, numberResult);
      }
      if (status.isOk()) {
        result.values[result.count] = nullValue ? 0 : numberResult.value;
        if (nullValue) {
          result.nullMask |= 1L << result.count;
        }
        result.count++;
      }
      if (!status.isOk() || consumeCharacter(sql, ')')) {
        break;
      }
      status = requireCharacter(sql, ',');
    }
    return status.isOk() && result.count >= 2
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
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
      status = selectColumnIdentifier(sql, result);
    }
    if (status.isOk()
        && (result.isNullProjection(0)
            || result.columnAlias(0).length() > 0)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    if (status.isOk()) {
      status = optionalColumnAlias(sql, result, 0);
    }
    if (status.isOk()) {
      status = requireKeyword(sql, "FROM");
    }
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
    if (offset >= sql.length()
        || sql.charAt(offset) == ','
        || sql.charAt(offset) == ';'
        || sql.charAt(offset) == ')'
        || nextKeyword(sql, "FROM")
        || nextKeyword(sql, "JOIN")
        || nextKeyword(sql, "WHERE")
        || nextKeyword(sql, "GROUP")
        || nextKeyword(sql, "ORDER")
        || nextKeyword(sql, "LIMIT")) {
      return StatusCode.OK;
    }
    return identifierStart(sql.charAt(offset))
        ? identifier(sql, result.writableColumnAlias(columnIndex))
        : StatusCode.OK;
  }

  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    skipSpaces(sql);
    if (offset >= sql.length() || !identifierStart(sql.charAt(offset))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    while (offset < sql.length() && identifierPart(sql.charAt(offset))) {
      if (result.length() >= SqlIdentifier.MAXIMUM_LENGTH) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      result.append(lower(sql.charAt(offset++)));
    }
    return StatusCode.OK;
  }

  private StatusCode optionalTableAlias(
      CharSequence sql,
      SqlCommand result) {
    if (consumeKeyword(sql, "AS")) {
      return identifier(sql, result.writableTableAlias());
    }
    skipSpaces(sql);
    if (offset >= sql.length()
        || sql.charAt(offset) == ';'
        || sql.charAt(offset) == ')'
        || nextKeyword(sql, "JOIN")
        || nextKeyword(sql, "WHERE")
        || nextKeyword(sql, "GROUP")
        || nextKeyword(sql, "ORDER")
        || nextKeyword(sql, "LIMIT")) {
      return StatusCode.OK;
    }
    return identifierStart(sql.charAt(offset))
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
    if (offset >= sql.length()
        || nextKeyword(sql, "ON")) {
      return StatusCode.OK;
    }
    return identifierStart(sql.charAt(offset))
        ? identifier(sql, result.writableJoinTableAlias())
        : StatusCode.OK;
  }

  private boolean nextKeyword(CharSequence sql, String keyword) {
    int start = offset;
    boolean matches = consumeKeyword(sql, keyword);
    offset = start;
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
    skipSpaces(sql);
    if (offset >= sql.length()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean negative = sql.charAt(offset) == '-';
    if (negative) {
      offset++;
    }
    if (offset >= sql.length() || !digit(sql.charAt(offset))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
    long multiplyMinimum = limit / 10;
    long value = 0;
    while (offset < sql.length() && digit(sql.charAt(offset))) {
      int digit = sql.charAt(offset++) - '0';
      if (value < multiplyMinimum) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      value *= 10;
      if (value < limit + digit) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      value -= digit;
    }
    result.value = negative ? value : -value;
    return StatusCode.OK;
  }

  private boolean startsNumber(CharSequence sql) {
    skipSpaces(sql);
    if (offset >= sql.length()) {
      return false;
    }
    char first = sql.charAt(offset);
    return digit(first)
        || first == '-'
            && offset + 1 < sql.length()
            && digit(sql.charAt(offset + 1));
  }

  private StatusCode requireKeyword(CharSequence sql, String keyword) {
    return consumeKeyword(sql, keyword) ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private boolean consumeKeyword(CharSequence sql, String keyword) {
    skipSpaces(sql);
    if (sql.length() - offset < keyword.length()) {
      return false;
    }
    for (int index = 0; index < keyword.length(); index++) {
      if (upper(sql.charAt(offset + index)) != keyword.charAt(index)) {
        return false;
      }
    }
    int end = offset + keyword.length();
    if (end < sql.length() && identifierPart(sql.charAt(end))) {
      return false;
    }
    offset = end;
    return true;
  }

  private StatusCode requireCharacter(CharSequence sql, char expected) {
    skipSpaces(sql);
    if (offset >= sql.length() || sql.charAt(offset) != expected) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    offset++;
    return StatusCode.OK;
  }

  private boolean consumeCharacter(CharSequence sql, char expected) {
    skipSpaces(sql);
    if (offset >= sql.length() || sql.charAt(offset) != expected) {
      return false;
    }
    offset++;
    return true;
  }

  private boolean finish(CharSequence sql) {
    skipSpaces(sql);
    if (offset < sql.length() && sql.charAt(offset) == ';') {
      offset++;
      skipSpaces(sql);
    }
    return offset == sql.length();
  }

  private void skipSpaces(CharSequence sql) {
    while (offset < sql.length() && Character.isWhitespace(sql.charAt(offset))) {
      offset++;
    }
  }

  private static char upper(char character) {
    return character >= 'a' && character <= 'z' ? (char) (character - 32) : character;
  }

  private static char lower(char character) {
    return character >= 'A' && character <= 'Z' ? (char) (character + 32) : character;
  }

  private static boolean identifierStart(char character) {
    return character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z'
        || character == '_';
  }

  private static boolean identifierPart(char character) {
    return identifierStart(character) || digit(character);
  }

  private static boolean digit(char character) {
    return character >= '0' && character <= '9';
  }

  private static final class LongResult {
    private long value;
  }

  private static final class LongRow {
    private final long[] values = new long[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlIdentifier identifier = new SqlIdentifier();
    private int count;
    private long nullMask;
  }

  private static final class SqlSourceView implements CharSequence {
    private String source;
    private int firstStart;
    private int firstLength;
    private int secondStart;
    private int secondLength;

    void set(
        String text,
        int firstFrom,
        int firstTo,
        int secondFrom,
        int secondTo) {
      source = text;
      firstStart = firstFrom;
      firstLength = firstTo - firstFrom;
      secondStart = secondFrom;
      secondLength = secondTo - secondFrom;
    }

    @Override
    public int length() {
      return firstLength + secondLength;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length()) {
        throw new IndexOutOfBoundsException(index);
      }
      return index < firstLength
          ? source.charAt(firstStart + index)
          : source.charAt(secondStart + index - firstLength);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class SqlScalarSourceView implements CharSequence {
    private String source;
    private int firstStart;
    private int firstLength;
    private int secondStart;
    private int secondLength;

    void set(
        String text,
        int firstFrom,
        int firstTo,
        int secondFrom,
        int secondTo,
        boolean includeEquality) {
      source = text;
      firstStart = firstFrom;
      firstLength = firstTo - firstFrom;
      secondStart = secondFrom;
      secondLength = secondTo - secondFrom;
      equality = includeEquality;
    }

    @Override
    public int length() {
      return firstLength + (equality ? 2 : 1) + secondLength;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length()) {
        throw new IndexOutOfBoundsException(index);
      }
      if (index < firstLength) {
        return source.charAt(firstStart + index);
      }
      if (equality && index == firstLength) {
        return '=';
      }
      int replacement = firstLength + (equality ? 1 : 0);
      return index == replacement
          ? '0' : source.charAt(secondStart + index - replacement - 1);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }

    boolean isReplacement(int index) {
      return index == firstLength + (equality ? 1 : 0);
    }

    private boolean equality;
  }
}
