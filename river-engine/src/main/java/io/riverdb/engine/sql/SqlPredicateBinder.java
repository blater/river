package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlQuery;

/** Resolves predicates and selects the best reusable access predicate. */
final class SqlPredicateBinder {
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();
  private final SqlRowProjectionProgramBinder rowPrograms =
      new SqlRowProjectionProgramBinder();

  StatusCode bind(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    reset(command, bound);
    bound.projectionPrograms.beginPredicate();
    int accessScore = -1;
    for (int index = 0; index < bound.predicateCount; index++) {
      StatusCode status = resolve(command, query, bound, index);
      if (!status.isOk()) {
        return status;
      }
      int score = accessScore(command, query, bound, index);
      if (score > accessScore
          && selectAccess(command, bound.table, bound, index, bound.predicateColumns[index])) {
        accessScore = score;
      }
    }
    return StatusCode.OK;
  }

  StatusCode bindJoin(SqlCommand command, BoundSqlStatement bound) {
    if (command.hasDisjunction()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    reset(command, bound);
    int accessScore = -1;
    for (int index = 0; index < bound.predicateCount; index++) {
      StatusCode status = resolveJoin(command, bound, index);
      if (!status.isOk()) {
        return status;
      }
      int column = bound.predicateColumns[index];
      if (column < 0) {
        continue;
      }
      int score = accessScore(command, bound.table, column, index);
      TableDefinition definition = column >= 0 ? bound.table : bound.joinTable;
      int resolvedColumn = column >= 0 ? column : -column - 1;
      if (score > accessScore
          && selectAccess(command, definition, bound, index, resolvedColumn)) {
        accessScore = score;
        bound.predicateColumn = column;
      }
    }
    return StatusCode.OK;
  }

  private static void reset(SqlCommand command, BoundSqlStatement bound) {
    bound.predicateCount = command.predicateCount();
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
  }

  private StatusCode resolve(
      SqlCommand command,
      SqlQuery query,
      BoundSqlStatement bound,
      int index) {
    if (command.predicateExpression(index) != null) {
      return resolveComputed(command, query, bound, index);
    }
    CharSequence qualifier = command.predicateTableName(index);
    if (qualifier.length() > 0
        && !SqlBindingNames.matchesTable(command, qualifier)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int column = bound.table.findColumn(command.predicateColumnName(index));
    if (isInvalidShape(command, index, column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (requiresLiteralTypeCheck(command, query, index)
        && !SqlTypeDescriptor.canCompare(
            bound.table.typeDescriptor(column),
            command.predicateTypeDescriptor(index))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!validComparison(
        bound.table.typeDescriptor(column), command.comparison(index))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    bound.predicateColumns[index] = column;
    return StatusCode.OK;
  }

  private StatusCode resolveComputed(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound, int index) {
    StatusCode status = rowPrograms.bindPredicate(
        command, bound, command.predicateExpression(index));
    if (!status.isOk()) return status;
    int descriptor = bound.projectionPrograms.resultDescriptor(
        SqlBoundProjectionPrograms.PREDICATE_LANE);
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && (command.isRangePredicate(index)
            || command.isLiteralMembership(index))) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (requiresLiteralTypeCheck(command, query, index)
        && !SqlTypeDescriptor.canCompare(
            descriptor, command.predicateTypeDescriptor(index))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!validComparison(descriptor, command.comparison(index))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    bound.predicateColumns[index] = SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
    return StatusCode.OK;
  }

  private static StatusCode resolveJoin(
      SqlCommand command, BoundSqlStatement bound, int index) {
    boolean outer = SqlBindingNames.matchesTable(
        command, command.predicateTableName(index));
    boolean inner = SqlBindingNames.matchesJoinTable(
        command, command.predicateTableName(index));
    TableDefinition definition = outer
        ? bound.table : inner ? bound.joinTable : null;
    int column = definition == null
        ? -1 : definition.findColumn(command.predicateColumnName(index));
    if (isInvalidShape(command, index, column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (requiresLiteralTypeCheck(command, null, index)
        && !SqlTypeDescriptor.canCompare(
            definition.typeDescriptor(column),
            command.predicateTypeDescriptor(index))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!validComparison(
        definition.typeDescriptor(column), command.comparison(index))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    bound.predicateColumns[index] = outer ? column : -column - 1;
    return StatusCode.OK;
  }

  private static boolean isInvalidShape(
      SqlCommand command, int index, int column) {
    return column < 0 || command.isColumnPredicate(index)
        || command.isRangePredicate(index)
            && command.predicateUpperExclusive(index)
                <= command.predicateLowerInclusive(index);
  }

  private static boolean requiresLiteralTypeCheck(
      SqlCommand command, SqlQuery query, int index) {
    if (command.isNullPredicate(index)) {
      return false;
    }
    if (command.isLiteralMembership(index)
        && command.literalMembershipCount(index) == 0
        && command.predicateTypeDescriptor(index) == 0) {
      return false;
    }
    return query == null
        || (!query.hasMembershipPredicate() || query.membershipPredicate() != index)
            && (!query.hasScalarPredicate() || query.scalarPredicate() != index);
  }

  private static boolean validComparison(int descriptor, SqlComparison comparison) {
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_BOOLEAN) {
      return true;
    }
    return comparison == null
        || comparison == SqlComparison.EQUAL
        || comparison == SqlComparison.NOT_EQUAL
        || comparison == SqlComparison.IN
        || comparison == SqlComparison.NOT_IN;
  }

  private static int accessScore(
      SqlCommand command,
      SqlQuery query,
      BoundSqlStatement bound,
      int index) {
    if (command.predicateExpression(index) != null
        || command.hasDisjunction()
        || query.hasMembershipPredicate() && query.membershipPredicate() == index
        || query.hasScalarPredicate() && query.scalarPredicate() == index) {
      return -1;
    }
    return accessScore(command, bound.table, bound.predicateColumns[index], index);
  }

  private static int accessScore(
      SqlCommand command,
      TableDefinition table,
      int column,
      int index) {
    if (command.isNullPredicate(index)
        || !command.isEqualityPredicate(index)
            && !command.isRangePredicate(index)) {
      return -1;
    }
    boolean indexed = column == 0
        || table.hasIndexOn(column) && !table.isVarchar(column);
    if (!indexed) {
      return 0;
    }
    if (!command.isEqualityPredicate(index)) {
      return 1;
    }
    return column == 0 || table.hasUniqueIndexOn(column) ? 3 : 2;
  }

  private boolean selectAccess(
      SqlCommand command,
      TableDefinition table,
      BoundSqlStatement bound,
      int predicate,
      int column) {
    long value = command.predicateValue(predicate);
    long lower = command.predicateLowerInclusive(predicate);
    long upper = command.predicateUpperExclusive(predicate);
    int target = table.typeDescriptor(column);
    int source = command.predicateTypeDescriptor(predicate);
    if (SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        && source != target) {
      if (command.isRangePredicate(predicate)) {
        if (!ExactDecimal.ceilingScale(lower, source, target, decimal)) {
          return false;
        }
        lower = decimal.value;
        if (!ExactDecimal.ceilingScale(upper, source, target, decimal)) {
          return false;
        }
        upper = decimal.value;
      } else {
        boolean converted = SqlTypeDescriptor.canImplicitlyCast(source, target)
            ? ExactDecimal.widenScale(value, source, target, decimal)
            : ExactDecimal.quantize(
                    value,
                    source,
                    target,
                    false,
                    true,
                    decimal,
                    wide)
                .isOk();
        if (!converted) {
          return false;
        }
        value = decimal.value;
      }
    }
    bound.accessPredicate = predicate;
    bound.predicateColumn = column;
    bound.accessValue = value;
    bound.accessLowerInclusive = lower;
    bound.accessUpperExclusive = upper;
    return true;
  }
}
