package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses bounded WHERE predicates and owns their literal normalization scratch. */
final class SqlPredicateParser {
  private final SqlParserInput input;
  private final SqlComparisonParser comparisons;
  private final SqlParser.LongResult numberResult = new SqlParser.LongResult();
  private final ExactDecimal.LongValue decimalResult = new ExactDecimal.LongValue();
  private final SqlIdentifier identifierScratch = new SqlIdentifier();
  private final PredicateResult predicateResult = new PredicateResult();
  private final long[] literalMembershipValues =
      new long[SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES];
  private int syntheticPredicateOffset = -1;
  private int syntheticPredicateIndex = -1;

  SqlPredicateParser(SqlParserInput parserInput) {
    input = parserInput;
    comparisons = new SqlComparisonParser(parserInput);
  }

  void beginStandard() {
    syntheticPredicateOffset = -1;
    syntheticPredicateIndex = -1;
  }

  void beginSynthetic(int replacementOffset) {
    syntheticPredicateOffset = replacementOffset;
    syntheticPredicateIndex = -1;
  }

  int syntheticPredicateIndex() {
    return syntheticPredicateIndex;
  }

  StatusCode parse(
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

  SqlComparison comparisonOperator(CharSequence sql) {
    return comparisons.parse(sql);
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



  private StatusCode matchingIdentifier(CharSequence sql, CharSequence expected) {
    identifierScratch.reset();
    StatusCode status = input.identifier(sql, identifierScratch);
    if (!status.isOk()) {
      return status;
    }
    return sameIdentifier(identifierScratch, expected)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
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

  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    return input.identifier(sql, result);
  }

  private StatusCode number(CharSequence sql, SqlParser.LongResult result) {
    return input.number(sql, result);
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

  private void skipSpaces(CharSequence sql) {
    input.skipSpaces(sql);
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
}
