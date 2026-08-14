package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlComparison;
import io.riverdb.storage.heap.HeapRowResult;

/** Evaluates the predicates of the currently bound statement without allocating. */
final class SqlBoundPredicateEvaluator {
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlExpressionEvaluator expressions;
  private final SqlNestedQueryExecution nested;

  SqlBoundPredicateEvaluator(
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlNestedQueryExecution nestedExecution) {
    bound = statement;
    query = statement.executableQuery;
    expressions = evaluator;
    nested = nestedExecution;
  }

  boolean matches(long primaryKey, HeapRowResult source) {
    if (nested.rejectsOuterRow()) {
      return false;
    }
    BoundSqlQuery.Block command = query.root();
    boolean conjunction = true;
    for (int index = 0; index < bound.predicateCount; index++) {
      if (command.predicateStartsDisjunction(index)) {
        if (conjunction) {
          return true;
        }
        conjunction = true;
      }
      if (conjunction && !matches(primaryKey, source, command, index)) {
        conjunction = false;
      }
    }
    return conjunction;
  }

  boolean matchesJoin(long primaryKey, HeapRowResult source, boolean outer) {
    BoundSqlQuery.Block command = query.root();
    for (int index = 0; index < bound.predicateCount; index++) {
      int descriptor = bound.predicateColumns[index];
      if (outer == (descriptor >= 0)
          && !matchesJoin(primaryKey, source, outer, descriptor, command, index)) {
        return false;
      }
    }
    return true;
  }

  boolean matchesNullExtendedJoin() {
    BoundSqlQuery.Block command = query.root();
    for (int index = 0; index < bound.predicateCount; index++) {
      if (bound.predicateColumns[index] < 0
          && (!command.isNullPredicate(index)
              || command.isNullPredicateNegated(index))) {
        return false;
      }
    }
    return true;
  }

  private boolean matches(
      long primaryKey,
      HeapRowResult source,
      BoundSqlQuery.Block command,
      int index) {
    int column = bound.predicateColumns[index];
    boolean nullValue = expressions.isNull(source, bound.table, column);
    if (command.isNullPredicate(index)) {
      return nullValue != command.isNullPredicateNegated(index);
    }
    if (nullValue) {
      return false;
    }
    long value = expressions.readColumn(primaryKey, source, column);
    if (query.hasMembershipPredicate() && query.membershipPredicate() == index) {
      return nested.matchesMembership(
          value, bound.table.typeDescriptor(column), source, column);
    }
    if (query.hasScalarPredicate() && query.scalarPredicate() == index) {
      return nested.matchesScalar(value, bound.table.typeDescriptor(column));
    }
    return bound.table.isVarchar(column)
        ? matchesText(source, bound.table, column, command, index)
        : matches(
            value,
            bound.table.typeDescriptor(column),
            command,
            index);
  }

  private boolean matchesJoin(
      long primaryKey,
      HeapRowResult source,
      boolean outer,
      int descriptor,
      BoundSqlQuery.Block command,
      int predicate) {
    int column = outer ? descriptor : -descriptor - 1;
    TableDefinition definition = outer ? bound.table : bound.joinTable;
    boolean nullValue = expressions.isNull(source, definition, column);
    if (command.isNullPredicate(predicate)) {
      return nullValue != command.isNullPredicateNegated(predicate);
    }
    if (nullValue) {
      return false;
    }
    long value = expressions.readColumn(primaryKey, source, column);
    return definition.isVarchar(column)
        ? matchesText(source, definition, column, command, predicate)
        : matches(
            value,
            definition.typeDescriptor(column),
            command,
            predicate);
  }

  private boolean matches(
      long actual,
      int actualDescriptor,
      BoundSqlQuery.Block source,
      int predicate) {
    SqlComparison comparison = source.comparison(predicate);
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return expressions.compareExact(
              actual,
              actualDescriptor,
              source.predicateLowerInclusive(predicate),
              source.predicateTypeDescriptor(predicate)) >= 0
          && expressions.compareExact(
              actual,
              actualDescriptor,
              source.predicateUpperExclusive(predicate),
              source.predicateTypeDescriptor(predicate)) < 0;
    }
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = contains(actual, actualDescriptor, source, predicate);
      return comparison == SqlComparison.IN
          ? equal : !equal && !source.literalMembershipHasNull(predicate);
    }
    return expressions.matchesComparison(
        actual,
        actualDescriptor,
        comparison,
        source.predicateValue(predicate),
        source.predicateTypeDescriptor(predicate));
  }

  private boolean matchesText(
      HeapRowResult actual,
      TableDefinition definition,
      int column,
      BoundSqlQuery.Block expected,
      int predicate) {
    SqlComparison comparison = expected.comparison(predicate);
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = false;
      for (int index = 0; index < expected.literalMembershipCount(predicate); index++) {
        if (compareText(
            actual,
            column,
            expected,
            expected.literalMembershipValue(predicate, index)) == 0) {
          equal = true;
          break;
        }
      }
      return comparison == SqlComparison.IN
          ? equal : !equal && !expected.literalMembershipHasNull(predicate);
    }
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return compareText(
              actual,
              column,
              expected,
              expected.predicateLowerInclusive(predicate)) >= 0
          && compareText(
              actual,
              column,
              expected,
              expected.predicateUpperExclusive(predicate)) < 0;
    }
    int compared = compareText(
        actual, column, expected, expected.predicateValue(predicate));
    return switch (comparison) {
      case EQUAL -> compared == 0;
      case NOT_EQUAL -> compared != 0;
      case LESS_THAN -> compared < 0;
      case LESS_OR_EQUAL -> compared <= 0;
      case GREATER_THAN -> compared > 0;
      case GREATER_OR_EQUAL -> compared >= 0;
      case HALF_OPEN_RANGE, IN, NOT_IN -> false;
    };
  }

  private int compareText(
      HeapRowResult actual,
      int column,
      BoundSqlQuery.Block expected,
      long expectedHandle) {
    long actualHandle = actual.getLong((column - 1) * Long.BYTES);
    int actualOffset = (int) (actualHandle >>> 32);
    int actualLength = (int) actualHandle;
    int expectedLength = expected.textByteLength(expectedHandle);
    if (actualOffset < 0 || actualLength < 0 || expectedLength < 0) {
      return Integer.MIN_VALUE;
    }
    int common = Math.min(actualLength, expectedLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(actual.getByte(actualOffset + index)),
          Byte.toUnsignedInt(expected.textByteAt(expectedHandle, index)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(actualLength, expectedLength);
  }

  private boolean contains(
      long actual,
      int actualDescriptor,
      BoundSqlQuery.Block source,
      int predicate) {
    int lower = 0;
    int upper = source.literalMembershipCount(predicate);
    while (lower < upper) {
      int middle = (lower + upper) >>> 1;
      long candidate = source.literalMembershipValue(predicate, middle);
      int compared = expressions.compareExact(
          candidate,
          source.predicateTypeDescriptor(predicate),
          actual,
          actualDescriptor);
      if (compared < 0) {
        lower = middle + 1;
      } else if (compared > 0) {
        upper = middle;
      } else {
        return true;
      }
    }
    return false;
  }
}
