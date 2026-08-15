package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Binds bounded post-aggregate programs against finalized aggregate slots. */
final class SqlPostAggregateProgramBinder {
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final boolean[] untypedNulls =
      new boolean[SqlScalarExpression.MAXIMUM_NODES];
  private int size;

  StatusCode bind(SqlCommand command, BoundSqlStatement bound) {
    bound.havingPrograms.begin(command.havingPredicateCount());
    for (int predicate = 0;
        predicate < command.havingPredicateCount(); predicate++) {
      StatusCode status = bindPredicate(command, bound, predicate);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode bindPredicate(
      SqlCommand command, BoundSqlStatement bound, int predicate) {
    size = 0;
    for (int node = 0; node < command.havingNodeCount(predicate); node++) {
      StatusCode status = bindNode(command, bound, predicate, node);
      if (!status.isOk()) return status;
    }
    if (size != 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int result = descriptors[0];
    StatusCode status = validateLiteral(command, predicate, result);
    if (status.isOk()) bound.havingPrograms.finish(predicate, result);
    return status;
  }

  private StatusCode bindNode(
      SqlCommand command, BoundSqlStatement bound, int predicate, int node) {
    int operator = command.havingOperator(predicate, node);
    long operand = command.havingOperand(predicate, node);
    if (operator == SqlScalarExpression.AGGREGATE_VALUE) {
      int invocation = (int) operand;
      if (invocation < 0 || invocation >= bound.aggregates.count()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int descriptor = bound.aggregates.resultDescriptor(invocation);
      return directTextOnly(command, predicate, descriptor)
          ? StatusCode.FEATURE_NOT_SUPPORTED
          : push(bound, predicate, operator, operand, descriptor, false);
    }
    if (operator == SqlScalarExpression.GROUP_VALUE) {
      int descriptor = bound.projectedTypeDescriptors[0];
      return directTextOnly(command, predicate, descriptor)
          ? StatusCode.FEATURE_NOT_SUPPORTED
          : push(bound, predicate, operator, 0, descriptor, false);
    }
    if (operator == SqlScalarExpression.NULL) {
      return push(bound, predicate, operator, 0, SqlTypeDescriptor.BIGINT, true);
    }
    if (SqlRowExpressionTypes.leaf(operator)) {
      int descriptor = command.havingNodeDescriptor(predicate, node);
      return SqlTypeDescriptor.isValid(descriptor)
          ? push(bound, predicate, operator, operand, descriptor, false)
          : StatusCode.DATATYPE_MISMATCH;
    }
    if (unary(operator)) return bindUnary(command, bound, predicate, node);
    return binary(operator)
        ? bindBinary(command, bound, predicate, node)
        : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private StatusCode bindUnary(
      SqlCommand command, BoundSqlStatement bound, int predicate, int node) {
    if (size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int slot = size - 1;
    int operator = command.havingOperator(predicate, node);
    int target = command.havingNodeDescriptor(predicate, node);
    int descriptor = operator == SqlScalarExpression.CAST && untypedNulls[slot]
        ? target : SqlPostAggregateExpressionTypes.unary(
            operator, descriptors[slot], target,
            command.havingOperand(predicate, node));
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    descriptors[slot] = descriptor;
    if (operator == SqlScalarExpression.CAST) untypedNulls[slot] = false;
    append(command, bound, predicate, node, descriptor);
    return StatusCode.OK;
  }

  private StatusCode bindBinary(
      SqlCommand command, BoundSqlStatement bound, int predicate, int node) {
    if (size < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int right = --size;
    int left = size - 1;
    int operator = command.havingOperator(predicate, node);
    if (!resolveNulls(operator, left, right)) return StatusCode.DATATYPE_MISMATCH;
    int descriptor = SqlPostAggregateExpressionTypes.binary(
        operator, descriptors[left], descriptors[right]);
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    descriptors[left] = descriptor;
    append(command, bound, predicate, node, descriptor);
    return StatusCode.OK;
  }

  private boolean resolveNulls(int operator, int left, int right) {
    if (!untypedNulls[left] && !untypedNulls[right]) return true;
    if (operator == SqlScalarExpression.ADD
        && SqlTypeDescriptor.typeId(descriptors[left])
            == SqlTypeDescriptor.TYPE_ID_DATE
        && untypedNulls[right]) {
      descriptors[right] = SqlTypeDescriptor.BIGINT;
      untypedNulls[right] = false;
      return true;
    }
    if (untypedNulls[left] == untypedNulls[right]) return false;
    int known = untypedNulls[right] ? descriptors[left] : descriptors[right];
    if (SqlTypeDescriptor.comparisonFamily(known)
        != SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC) return false;
    descriptors[untypedNulls[right] ? right : left] = known;
    untypedNulls[left] = false;
    untypedNulls[right] = false;
    return true;
  }

  private StatusCode push(
      BoundSqlStatement bound,
      int predicate,
      int operator,
      long operand,
      int descriptor,
      boolean untypedNull) {
    if (size >= descriptors.length) return StatusCode.RESOURCE_EXHAUSTED;
    descriptors[size] = descriptor;
    untypedNulls[size++] = untypedNull;
    bound.havingPrograms.append(predicate, operator, operand, descriptor);
    return StatusCode.OK;
  }

  private static void append(
      SqlCommand command,
      BoundSqlStatement bound,
      int predicate,
      int node,
      int descriptor) {
    bound.havingPrograms.append(
        predicate,
        command.havingOperator(predicate, node),
        command.havingOperand(predicate, node),
        descriptor);
  }

  private static StatusCode validateLiteral(
      SqlCommand command, int predicate, int result) {
    if (command.havingNullPredicate(predicate)) return StatusCode.OK;
    SqlComparison comparison = command.havingComparison(predicate);
    if (SqlTypeDescriptor.typeId(result) == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        && comparison != SqlComparison.EQUAL
        && comparison != SqlComparison.NOT_EQUAL
        && comparison != SqlComparison.IN
        && comparison != SqlComparison.NOT_IN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return comparable(result, command.havingValueDescriptor(predicate),
          command.havingValueNull(predicate))
              && comparable(result, command.havingUpperDescriptor(predicate),
                  command.havingUpperNull(predicate))
          ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
    }
    return comparable(result, command.havingValueDescriptor(predicate),
        command.havingValueNull(predicate)
            || command.havingMemberCount(predicate) == 0
                && command.havingMembershipHasNull(predicate))
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  private static boolean comparable(int result, int literal, boolean nullValue) {
    return nullValue && literal == 0 || SqlTypeDescriptor.canCompare(result, literal);
  }

  private static boolean unary(int operator) {
    return SqlRowExpressionTypes.unary(operator)
        || SqlExactExpressionEvaluator.unaryOperator(operator);
  }

  private static boolean binary(int operator) {
    return operator >= SqlScalarExpression.ADD
        && operator <= SqlScalarExpression.REMAINDER;
  }

  private static boolean directTextOnly(
      SqlCommand command, int predicate, int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && command.havingNodeCount(predicate) != 1;
  }
}
