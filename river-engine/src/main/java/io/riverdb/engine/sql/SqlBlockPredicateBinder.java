package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;

/** Resolves one block's predicates to fixed child-schema column indices. */
final class SqlBlockPredicateBinder {
  private final SqlBlockExpressionBinder expressions;

  SqlBlockPredicateBinder(SqlBlockExpressionBinder expressionBinder) {
    expressions = expressionBinder;
  }

  StatusCode bind(
      SqlCommand command,
      SqlBlockSchema child,
      BoundSqlStatement bound,
      int block) {
    for (int predicate = 0; predicate < command.predicateCount(); predicate++) {
      StatusCode status = command.predicateExpression(predicate) == null
          ? raw(command, child, bound, predicate)
          : computed(command, child, bound, block, predicate);
      if (!status.isOk()) return status;
    }
    bound.predicateCount = command.predicateCount();
    return StatusCode.OK;
  }

  private StatusCode computed(
      SqlCommand command,
      SqlBlockSchema child,
      BoundSqlStatement bound,
      int block,
      int predicate) {
    if (block > 0) return StatusCode.FEATURE_NOT_SUPPORTED;
    bound.projectionPrograms.beginPredicate();
    StatusCode status = expressions.bind(
        command,
        command.predicateExpression(predicate),
        SqlBoundProjectionPrograms.PREDICATE_LANE,
        child,
        bound);
    if (!status.isOk()) return status;
    int descriptor = bound.projectionPrograms.resultDescriptor(
        SqlBoundProjectionPrograms.PREDICATE_LANE);
    SqlComparison comparison = command.comparison(predicate);
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && (comparison == SqlComparison.HALF_OPEN_RANGE
            || comparison == SqlComparison.IN
            || comparison == SqlComparison.NOT_IN)) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    bound.predicateColumns[predicate] = SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
    bound.blockPredicateRightColumns[predicate] = -1;
    return StatusCode.OK;
  }

  private static StatusCode raw(
      SqlCommand command,
      SqlBlockSchema child,
      BoundSqlStatement bound,
      int predicate) {
    int left = child.find(command.predicateColumnName(predicate));
    if (left < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    bound.predicateColumns[predicate] = left;
    bound.blockPredicateRightColumns[predicate] = -1;
    if (command.isNullPredicate(predicate)) return StatusCode.OK;
    int right = command.predicateTypeDescriptor(predicate);
    if (command.isColumnPredicate(predicate)) {
      int column = child.find(command.predicateValueColumnName(predicate));
      if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      bound.blockPredicateRightColumns[predicate] = column;
      right = child.descriptor(column);
    }
    if (!SqlTypeDescriptor.canCompare(child.descriptor(left), right)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    SqlComparison comparison = command.comparison(predicate);
    if (SqlTypeDescriptor.typeId(child.descriptor(left))
            == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        && comparison != SqlComparison.EQUAL
        && comparison != SqlComparison.NOT_EQUAL
        && comparison != SqlComparison.IN
        && comparison != SqlComparison.NOT_IN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return StatusCode.OK;
  }
}
