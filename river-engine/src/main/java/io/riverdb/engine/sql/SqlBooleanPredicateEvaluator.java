package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.storage.heap.HeapRowResult;

/** One lazy, short-circuiting SQL-3VL evaluator for canonical predicates. */
final class SqlBooleanPredicateEvaluator {
  static final int FALSE = 0;
  static final int TRUE = 1;
  static final int UNKNOWN = 2;

  private final SqlBooleanPredicateWorkspace workspace;
  private final SqlPredicateOperandEvaluator expressions;
  private final SqlExpressionEvaluator exact;
  private final SqlBooleanPredicatePreparation preparation;
  private final SqlPredicateOperand left;
  private final SqlPredicateOperand right;
  private final SqlPredicateOperand lower;
  private final SqlPredicateOperand upper;
  private SqlCommand command;
  private SqlBoundBooleanPredicateProgram programs;
  private long primaryKey;
  private HeapRowResult physicalRow;
  private TableDefinition table;
  private SqlBlockRow blockRow;
  private boolean block;
  private boolean join;
  private SqlJoinRoleRows joinRows;
  private SqlAggregateAccumulatorSet havingAggregates;
  private long havingGroupValue;
  private boolean havingGroupNull;
  private byte[] havingGroupText;
  private int havingGroupTextLength;
  private int truth;
  private SqlSubqueryLeafEvaluator subqueries;
  private SqlNestedRowProvider nestedRows;
  private final SqlSubqueryLeafEvaluator.Truth subqueryTruth =
      new SqlSubqueryLeafEvaluator.Truth();

  SqlBooleanPredicateEvaluator(
      SqlExpressionEvaluator columnReader, SqlTemporalContext temporalContext) {
    this(new SqlBooleanPredicateWorkspace(columnReader, temporalContext), temporalContext);
  }

  SqlBooleanPredicateEvaluator(
      SqlBooleanPredicateWorkspace shared,
      SqlTemporalContext temporalContext) {
    exact = shared.columns;
    preparation = new SqlBooleanPredicatePreparation(temporalContext);
    workspace = shared;
    expressions = shared.expressions;
    left = shared.left;
    right = shared.right;
    lower = shared.lower;
    upper = shared.upper;
  }

  StatusCode prepare(
      SqlCommand source, SqlBoundBooleanPredicateProgram bound) {
    resetOperands();
    return preparation.prepare(source, bound);
  }

  StatusCode matches(
      SqlCommand source,
      SqlBoundBooleanPredicateProgram bound,
      long key,
      HeapRowResult row,
      TableDefinition definition,
      Match result) {
    result.matched = false;
    if (!bound.available()) {
      result.matched = true;
      return StatusCode.OK;
    }
    command = source;
    programs = bound;
    primaryKey = key;
    physicalRow = row;
    table = definition;
    blockRow = null;
    block = false;
    StatusCode status = evaluateNode(bound.root());
    if (status.isOk()) result.matched = truth == TRUE;
    clearEvaluation();
    return status;
  }

  StatusCode matchesNested(
      SqlCommand source,
      SqlBoundBooleanPredicateProgram bound,
      long key,
      HeapRowResult row,
      TableDefinition definition,
      SqlSubqueryLeafEvaluator nested,
      SqlNestedRowProvider rows,
      Match result) {
    SqlSubqueryLeafEvaluator previous = subqueries;
    subqueries = nested;
    nestedRows = rows;
    StatusCode status = matches(source, bound, key, row, definition, result);
    subqueries = previous;
    nestedRows = null;
    return status;
  }

  StatusCode matchesBlock(
      SqlCommand source,
      SqlBoundBooleanPredicateProgram bound,
      SqlBlockRow row,
      Match result) {
    result.matched = false;
    if (!bound.available()) {
      result.matched = true;
      return StatusCode.OK;
    }
    command = source;
    programs = bound;
    primaryKey = 0;
    physicalRow = null;
    table = null;
    blockRow = row;
    block = true;
    StatusCode status = evaluateNode(bound.root());
    if (status.isOk()) result.matched = truth == TRUE;
    clearEvaluation();
    return status;
  }

  StatusCode matchesJoin(
      SqlCommand source,
      SqlBoundBooleanPredicateProgram bound,
      SqlJoinRoleRows rows,
      Match result) {
    result.matched = false;
    if (!bound.available()) {
      result.matched = true;
      return StatusCode.OK;
    }
    command = source;
    programs = bound;
    join = true;
    joinRows = rows;
    blockRow = null;
    block = false;
    StatusCode status = evaluateNode(bound.root());
    if (status.isOk()) result.matched = truth == TRUE;
    clearEvaluation();
    return status;
  }

  StatusCode matchesHaving(
      SqlCommand source,
      SqlBoundBooleanPredicateProgram bound,
      SqlAggregateAccumulatorSet aggregates,
      long groupValue,
      boolean groupNull,
      byte[] groupText,
      int groupTextLength,
      Match result) {
    result.matched = false;
    if (!bound.available()) {
      result.matched = true;
      return StatusCode.OK;
    }
    command = source;
    programs = bound;
    havingAggregates = aggregates;
    havingGroupValue = groupValue;
    havingGroupNull = groupNull;
    havingGroupText = groupText;
    havingGroupTextLength = groupTextLength;
    StatusCode status = evaluateNode(bound.root());
    if (status.isOk()) result.matched = truth == TRUE;
    clearEvaluation();
    return status;
  }

  private StatusCode evaluateNode(int node) {
    int operator = programs.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      return evaluateLeaf(programs.booleanLeft(node));
    }
    StatusCode status = evaluateNode(programs.booleanLeft(node));
    if (!status.isOk()) return status;
    int leftTruth = truth;
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_NOT) {
      truth = not(leftTruth);
      return StatusCode.OK;
    }
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND && leftTruth == FALSE
        || operator == SqlBooleanPredicateProgram.BOOLEAN_OR && leftTruth == TRUE) {
      return StatusCode.OK;
    }
    status = evaluateNode(programs.booleanRight(node));
    if (!status.isOk()) return status;
    truth = operator == SqlBooleanPredicateProgram.BOOLEAN_AND
        ? and(leftTruth, truth) : or(leftTruth, truth);
    return StatusCode.OK;
  }

  private StatusCode evaluateLeaf(int leaf) {
    int test = programs.leafTest(leaf);
    if (test == SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS) {
      return subquery(leaf, null);
    }
    StatusCode status = operand(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, left);
    if (!status.isOk()) return status;
    if (test == SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON
        || test == SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP) {
      return subquery(leaf, left);
    }
    if (test == SqlBooleanPredicateProgram.TEST_NULL) {
      truth = left.nullValue() != programs.negated(leaf) ? TRUE : FALSE;
      return StatusCode.OK;
    }
    if (test == SqlBooleanPredicateProgram.TEST_BOOLEAN) {
      truth = left.nullValue() ? UNKNOWN : left.value() == 0 ? FALSE : TRUE;
      return StatusCode.OK;
    }
    if (test == SqlBooleanPredicateProgram.TEST_TRUTH) {
      return explicitTruth(leaf);
    }
    if (test == SqlBooleanPredicateProgram.TEST_COMPARISON) {
      status = operand(leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, right);
      if (status.isOk()) truth = comparison(left, right, programs.comparison(leaf));
      return status;
    }
    if (test == SqlBooleanPredicateProgram.TEST_BETWEEN) {
      return between(leaf);
    }
    return test == SqlBooleanPredicateProgram.TEST_MEMBERSHIP
        ? membership(leaf) : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode subquery(int leaf, SqlPredicateOperand operand) {
    if (subqueries == null || programs.subqueryEdge(leaf) < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = subqueries.evaluate(
        programs.subqueryEdge(leaf), operand, subqueryTruth);
    if (status.isOk()) truth = subqueryTruth.value();
    return status;
  }

  private StatusCode explicitTruth(int leaf) {
    SqlComparison comparison = programs.comparison(leaf);
    boolean matched = comparison == null
        ? left.nullValue()
        : !left.nullValue()
            && (comparison == SqlComparison.EQUAL) == (left.value() != 0);
    if (programs.negated(leaf)) matched = !matched;
    truth = matched ? TRUE : FALSE;
    return StatusCode.OK;
  }

  private StatusCode between(int leaf) {
    StatusCode status = operand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER, lower);
    if (!status.isOk()) return status;
    int lowerTruth = comparison(left, lower, SqlComparison.GREATER_OR_EQUAL);
    if (lowerTruth == FALSE) {
      truth = programs.negated(leaf) ? TRUE : FALSE;
      return StatusCode.OK;
    }
    status = operand(leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER, upper);
    if (!status.isOk()) return status;
    truth = and(lowerTruth, comparison(left, upper, SqlComparison.LESS_OR_EQUAL));
    if (programs.negated(leaf)) truth = not(truth);
    return StatusCode.OK;
  }

  private StatusCode membership(int leaf) {
    if (left.nullValue()) {
      truth = UNKNOWN;
      return StatusCode.OK;
    }
    boolean unknown = false;
    for (int member = 0; member < programs.memberCount(leaf); member++) {
      if (programs.memberNull(leaf, member)) {
        unknown = true;
        continue;
      }
      int compared = compareMember(leaf, member);
      if (compared == Integer.MIN_VALUE) return StatusCode.CORRUPTION;
      if (compared == 0) {
        truth = programs.negated(leaf) ? FALSE : TRUE;
        return StatusCode.OK;
      }
    }
    truth = unknown ? UNKNOWN : programs.negated(leaf) ? TRUE : FALSE;
    return StatusCode.OK;
  }

  private int comparison(
      SqlPredicateOperand first,
      SqlPredicateOperand second,
      SqlComparison comparison) {
    if (first.nullValue() || second.nullValue()) return UNKNOWN;
    int compared = text(first.descriptor())
        ? SqlBooleanTextComparator.compare(first, second)
        : exact.compareExact(
            first.value(), first.descriptor(), second.value(), second.descriptor());
    return matches(compared, comparison) ? TRUE : FALSE;
  }

  private int compareMember(int leaf, int member) {
    int descriptor = programs.memberDescriptor(leaf, member);
    return text(left.descriptor())
        ? SqlBooleanTextComparator.compareLiteral(
            left, command, programs.member(leaf, member))
        : exact.compareExact(
            left.value(), left.descriptor(), programs.member(leaf, member), descriptor);
  }

  private StatusCode operand(int leaf, int program, SqlPredicateOperand result) {
    SqlTemporalZonePlan zone = preparation.zone(leaf, program);
    if (havingAggregates != null) {
      return expressions.evaluateHaving(
          command,
          programs,
          leaf,
          program,
          zone,
          havingAggregates,
          havingGroupValue,
          havingGroupNull,
          havingGroupText,
          havingGroupTextLength,
          result);
    }
    if (nestedRows != null) {
      return expressions.evaluateNested(
          command, programs, leaf, program, zone, nestedRows, result);
    }
    if (join) {
      return expressions.evaluateJoin(
          command,
          programs,
          leaf,
          program,
          zone,
          joinRows,
          result);
    }
    return expressions.evaluate(
        command,
        programs,
        leaf,
        program,
        zone,
        block ? 0 : primaryKey,
        block ? null : physicalRow,
        block ? null : table,
        block ? blockRow : null,
        result);
  }

  private static boolean matches(int compared, SqlComparison comparison) {
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

  private static boolean text(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private static int not(int value) {
    return value == UNKNOWN ? UNKNOWN : value == TRUE ? FALSE : TRUE;
  }

  private static int and(int first, int second) {
    if (first == FALSE || second == FALSE) return FALSE;
    return first == TRUE && second == TRUE ? TRUE : UNKNOWN;
  }

  private static int or(int first, int second) {
    if (first == TRUE || second == TRUE) return TRUE;
    return first == FALSE && second == FALSE ? FALSE : UNKNOWN;
  }

  private void clearEvaluation() {
    command = null;
    programs = null;
    primaryKey = 0;
    physicalRow = null;
    table = null;
    blockRow = null;
    block = false;
    join = false;
    joinRows = null;
    havingAggregates = null;
    havingGroupValue = 0;
    havingGroupNull = false;
    havingGroupText = null;
    havingGroupTextLength = 0;
    truth = FALSE;
    subqueries = null;
    nestedRows = null;
    resetOperands();
  }

  private void resetOperands() {
    workspace.clearOperands();
  }

  void reset() {
    clearEvaluation();
    workspace.reset();
    preparation.reset();
  }

  static final class Match {
    private boolean matched;

    boolean matched() { return matched; }
  }
}
