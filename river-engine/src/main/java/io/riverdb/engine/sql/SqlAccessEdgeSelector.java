package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Selects one normalized physical edge from mandatory Boolean AND leaves. */
final class SqlAccessEdgeSelector {
  private static final int MAXIMUM_EDGES = SqlBooleanPredicateProgram.MAXIMUM_LEAVES;
  private final int[] columns = new int[MAXIMUM_EDGES];
  private final int[] leaves = new int[MAXIMUM_EDGES];
  private final SqlComparison[] comparisons = new SqlComparison[MAXIMUM_EDGES];
  private final long[] values = new long[MAXIMUM_EDGES];
  private final int[] descriptors = new int[MAXIMUM_EDGES];
  private final long[] upperValues = new long[MAXIMUM_EDGES];
  private final int[] upperDescriptors = new int[MAXIMUM_EDGES];
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();
  private int count;
  private long convertedValue;
  private long normalizedLower;
  private long normalizedUpper;
  private SqlBoundJoinContext joinContext;

  void select(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram programs,
      BoundSqlStatement bound) {
    joinContext = null;
    count = 0;
    collect(source, programs, programs.root());
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
    bound.pointTextColumn = -1;
    bound.accessComparison = null;
    selectTextPoint(bound);
    int best = -1;
    for (int edge = 0; edge < count; edge++) {
      if (comparisons[edge] != SqlComparison.EQUAL) continue;
      int score = score(bound.table, columns[edge], comparisons[edge]);
      if (score > best && publish(bound, edge)) best = score;
    }
    if (best >= 3) {
      bound.pointTextColumn = -1;
    } else if (bound.pointTextColumn >= 0) {
      bound.accessPredicate = -1;
      bound.predicateColumn = -1;
      bound.accessComparison = null;
    } else {
      selectRange(bound, best);
    }
    clear();
  }

  void selectJoin(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram programs,
      SqlBoundJoinContext context) {
    joinContext = context;
    count = 0;
    collect(source, programs, programs.root());
    context.accessPredicate = -1;
    context.predicateColumn = -1;
    context.accessComparison = null;
    int best = -1;
    TableDefinition table = context.table(0);
    for (int edge = 0; edge < count; edge++) {
      if (comparisons[edge] != SqlComparison.EQUAL) continue;
      int score = score(table, columns[edge], comparisons[edge]);
      if (score > best && publish(context, edge)) best = score;
    }
    selectRange(context, best);
    clear();
  }

  private void selectTextPoint(BoundSqlStatement bound) {
    for (int edge = 0; edge < count; edge++) {
      int column = columns[edge];
      if (comparisons[edge] == SqlComparison.EQUAL
          && bound.table.isVarchar(column)
          && bound.table.hasUniqueIndexOn(column)) {
        bound.pointTextColumn = column;
        return;
      }
    }
  }

  private void collect(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram programs,
      int node) {
    int operator = programs.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      collect(source, programs, programs.booleanLeft(node));
      collect(source, programs, programs.booleanRight(node));
    } else if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      collectLeaf(source, programs, programs.booleanLeft(node));
    }
  }

  private void collectLeaf(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram programs,
      int leaf) {
    if (count >= MAXIMUM_EDGES) return;
    if (source.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_BETWEEN) {
      collectBetween(source, programs, leaf);
      return;
    }
    if (source.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON) return;
    int left = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    int right = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
    int column = programs.rawColumn(leaf, left);
    int literalProgram = right;
    SqlComparison comparison = source.comparison(leaf);
    if (column < 0) {
      column = programs.rawColumn(leaf, right);
      literalProgram = left;
      comparison = reverse(comparison);
    }
    if (column >= 0
        && !rootScope(
            programs.scope(leaf, literalProgram == right ? left : right, 0))) return;
    if (column < 0 || !literal(source, leaf, literalProgram)) return;
    columns[count] = column;
    leaves[count] = leaf;
    comparisons[count] = comparison;
    values[count] = source.programOperand(leaf, literalProgram, 0);
    descriptors[count] = programs.resultDescriptor(leaf, literalProgram);
    count++;
  }

  private void collectBetween(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram programs,
      int leaf) {
    int left = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    int lower = SqlBooleanPredicateProgram.PROGRAM_LOWER;
    int upper = SqlBooleanPredicateProgram.PROGRAM_UPPER;
    int column = programs.rawColumn(leaf, left);
    if (source.leafNegated(leaf) || column < 0
        || !rootScope(programs.scope(leaf, left, 0))
        || !literal(source, leaf, lower) || !literal(source, leaf, upper)) return;
    columns[count] = column;
    leaves[count] = leaf;
    comparisons[count] = SqlComparison.HALF_OPEN_RANGE;
    values[count] = source.programOperand(leaf, lower, 0);
    descriptors[count] = programs.resultDescriptor(leaf, lower);
    upperValues[count] = source.programOperand(leaf, upper, 0);
    upperDescriptors[count] = programs.resultDescriptor(leaf, upper);
    count++;
  }

  private boolean publish(BoundSqlStatement bound, int edge) {
    int column = columns[edge];
    int target = bound.table.typeDescriptor(column);
    SqlComparison comparison = comparisons[edge];
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return normalizeRange(
              values[edge], descriptors[edge], SqlComparison.GREATER_OR_EQUAL,
              upperValues[edge], upperDescriptors[edge], SqlComparison.LESS_OR_EQUAL,
              target)
          && publishRange(bound, edge, column);
    }
    if (comparison != SqlComparison.EQUAL) return false;
    if (!convert(values[edge], descriptors[edge], target, comparison)) return false;
    long value = convertedValue;
    bound.accessPredicate = leaves[edge];
    bound.predicateColumn = column;
    bound.accessComparison = comparison;
    bound.accessValue = value;
    bound.accessLowerInclusive = value;
    bound.accessUpperExclusive = value;
    return true;
  }

  private boolean publish(SqlBoundJoinContext context, int edge) {
    int column = columns[edge];
    int target = context.table(0).typeDescriptor(column);
    SqlComparison comparison = comparisons[edge];
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return normalizeRange(
              values[edge], descriptors[edge], SqlComparison.GREATER_OR_EQUAL,
              upperValues[edge], upperDescriptors[edge], SqlComparison.LESS_OR_EQUAL,
              target)
          && publishRange(context, edge, column);
    }
    if (comparison != SqlComparison.EQUAL
        || !convert(values[edge], descriptors[edge], target, comparison)) {
      return false;
    }
    long value = convertedValue;
    context.accessPredicate = leaves[edge];
    context.predicateColumn = column;
    context.accessComparison = comparison;
    context.accessValue = value;
    context.accessLowerInclusive = value;
    context.accessUpperExclusive = value;
    return true;
  }

  private void selectRange(BoundSqlStatement bound, int previousBest) {
    int best = previousBest;
    for (int edge = 0; edge < count; edge++) {
      int column = columns[edge];
      int score = score(bound.table, column, SqlComparison.HALF_OPEN_RANGE);
      if (score <= best || !rangeForColumn(column, bound.table.typeDescriptor(column))) {
        continue;
      }
      bound.accessPredicate = leaves[edge];
      bound.predicateColumn = column;
      bound.accessComparison = SqlComparison.HALF_OPEN_RANGE;
      bound.accessLowerInclusive = normalizedLower;
      bound.accessUpperExclusive = normalizedUpper;
      best = score;
    }
  }

  private void selectRange(SqlBoundJoinContext context, int previousBest) {
    int best = previousBest;
    TableDefinition table = context.table(0);
    for (int edge = 0; edge < count; edge++) {
      int column = columns[edge];
      int score = score(table, column, SqlComparison.HALF_OPEN_RANGE);
      if (score <= best || !rangeForColumn(column, table.typeDescriptor(column))) {
        continue;
      }
      context.accessPredicate = leaves[edge];
      context.predicateColumn = column;
      context.accessComparison = SqlComparison.HALF_OPEN_RANGE;
      context.accessLowerInclusive = normalizedLower;
      context.accessUpperExclusive = normalizedUpper;
      best = score;
    }
  }

  private boolean rangeForColumn(int column, int target) {
    long lower = SqlValueDomain.minimumFixed(target);
    long upper = SqlValueDomain.exclusiveMaximumFixed(target);
    boolean hasLower = false;
    boolean hasUpper = upper != Long.MIN_VALUE;
    boolean sawBound = false;
    for (int edge = 0; edge < count; edge++) {
      if (columns[edge] != column) continue;
      SqlComparison comparison = comparisons[edge];
      if (comparison == SqlComparison.HALF_OPEN_RANGE) {
        if (!normalizeBound(
            values[edge], descriptors[edge], target, SqlComparison.GREATER_OR_EQUAL)) {
          continue;
        }
        lower = hasLower ? Math.max(lower, convertedValue) : convertedValue;
        hasLower = true;
        sawBound = true;
        if (!normalizeBound(
            upperValues[edge], upperDescriptors[edge], target,
            SqlComparison.LESS_OR_EQUAL)) continue;
        upper = hasUpper ? Math.min(upper, convertedValue) : convertedValue;
        hasUpper = true;
        sawBound = true;
      } else if (lower(comparison)
          && normalizeBound(values[edge], descriptors[edge], target, comparison)) {
        lower = hasLower ? Math.max(lower, convertedValue) : convertedValue;
        hasLower = true;
        sawBound = true;
      } else if (upper(comparison)
          && normalizeBound(values[edge], descriptors[edge], target, comparison)) {
        upper = hasUpper ? Math.min(upper, convertedValue) : convertedValue;
        hasUpper = true;
        sawBound = true;
      }
    }
    if (!sawBound) return false;
    if (!hasLower) lower = SqlValueDomain.minimumFixed(target);
    if (!hasUpper) {
      upper = SqlValueDomain.exclusiveMaximumFixed(target);
      if (upper == Long.MIN_VALUE) return false;
    }
    normalizedLower = lower;
    normalizedUpper = upper;
    return lower < upper;
  }

  private boolean publishRange(
      BoundSqlStatement bound, int edge, int column) {
    bound.accessPredicate = leaves[edge];
    bound.predicateColumn = column;
    bound.accessComparison = SqlComparison.HALF_OPEN_RANGE;
    bound.accessLowerInclusive = normalizedLower;
    bound.accessUpperExclusive = normalizedUpper;
    return true;
  }

  private boolean publishRange(
      SqlBoundJoinContext context, int edge, int column) {
    context.accessPredicate = leaves[edge];
    context.predicateColumn = column;
    context.accessComparison = SqlComparison.HALF_OPEN_RANGE;
    context.accessLowerInclusive = normalizedLower;
    context.accessUpperExclusive = normalizedUpper;
    return true;
  }

  private boolean normalizeRange(
      long lowerValue,
      int lowerDescriptor,
      SqlComparison lowerComparison,
      long upperValue,
      int upperDescriptor,
      SqlComparison upperComparison,
      int target) {
    if (!normalizeBound(lowerValue, lowerDescriptor, target, lowerComparison)) {
      return false;
    }
    normalizedLower = convertedValue;
    if (!normalizeBound(upperValue, upperDescriptor, target, upperComparison)) {
      return false;
    }
    normalizedUpper = convertedValue;
    return normalizedLower < normalizedUpper;
  }

  private boolean normalizeBound(
      long value, int source, int target, SqlComparison comparison) {
    boolean exact = exact(value, source, target);
    if (!ceiling(value, source, target)) return false;
    long candidate = convertedValue;
    boolean successor = comparison == SqlComparison.GREATER_THAN
        || comparison == SqlComparison.LESS_OR_EQUAL;
    if (successor && exact) {
      if (candidate == Long.MAX_VALUE
          || !SqlValueDomain.validFixed(target, candidate + 1)) return false;
      candidate++;
    }
    convertedValue = candidate;
    return true;
  }

  private boolean exact(long value, int source, int target) {
    if (source == target) return true;
    if (!exactNumeric(source) || !exactNumeric(target)) {
      return true;
    }
    return ExactDecimal.quantize(
        value, source, target, false, true, decimal, wide).isOk();
  }

  private boolean ceiling(long value, int source, int target) {
    if (source == target || !exactNumeric(source) || !exactNumeric(target)) {
      convertedValue = value;
      return true;
    }
    boolean converted = ExactDecimal.ceilingScale(value, source, target, decimal);
    if (converted) convertedValue = decimal.value;
    return converted;
  }

  private boolean convert(
      long value, int source, int target, SqlComparison comparison) {
    if (source == target) {
      convertedValue = value;
      return true;
    }
    if (!exactNumeric(source) || !exactNumeric(target)) {
      convertedValue = value;
      return true;
    }
    boolean converted = comparison == SqlComparison.EQUAL
        ? SqlTypeDescriptor.canImplicitlyCast(source, target)
            ? ExactDecimal.widenScale(value, source, target, decimal)
            : ExactDecimal.quantize(
                value, source, target, false, true, decimal, wide).isOk()
        : ExactDecimal.ceilingScale(value, source, target, decimal);
    if (converted) convertedValue = decimal.value;
    return converted;
  }

  private static boolean exactNumeric(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_BIGINT
        || type == SqlTypeDescriptor.TYPE_ID_DECIMAL;
  }

  private static int score(
      TableDefinition table, int column, SqlComparison comparison) {
    if (comparison != SqlComparison.EQUAL
        && comparison != SqlComparison.HALF_OPEN_RANGE) return -1;
    boolean indexed = column == 0
        || table.hasIndexOn(column) && !table.isVarchar(column);
    if (!indexed) return -1;
    if (comparison == SqlComparison.HALF_OPEN_RANGE) return 1;
    return column == 0 || table.hasUniqueIndexOn(column) ? 3 : 2;
  }

  private static SqlComparison reverse(SqlComparison comparison) {
    return switch (comparison) {
      case LESS_THAN -> SqlComparison.GREATER_THAN;
      case LESS_OR_EQUAL -> SqlComparison.GREATER_OR_EQUAL;
      case GREATER_THAN -> SqlComparison.LESS_THAN;
      case GREATER_OR_EQUAL -> SqlComparison.LESS_OR_EQUAL;
      default -> comparison;
    };
  }

  private static boolean lower(SqlComparison comparison) {
    return comparison == SqlComparison.GREATER_THAN
        || comparison == SqlComparison.GREATER_OR_EQUAL;
  }

  private static boolean upper(SqlComparison comparison) {
    return comparison == SqlComparison.LESS_THAN
        || comparison == SqlComparison.LESS_OR_EQUAL;
  }

  private static boolean literal(
      SqlBooleanPredicateProgram source, int leaf, int program) {
    return source.programNodeCount(leaf, program) == 1
        && source.programOperator(leaf, program, 0) == SqlScalarExpression.LITERAL;
  }

  private void clear() {
    for (int edge = 0; edge < count; edge++) {
      columns[edge] = 0;
      leaves[edge] = 0;
      comparisons[edge] = null;
      values[edge] = 0;
      descriptors[edge] = 0;
      upperValues[edge] = 0;
      upperDescriptors[edge] = 0;
    }
    count = 0;
    joinContext = null;
  }

  private boolean rootScope(int scope) {
    return joinContext == null
        ? scope == SqlBoundBooleanPredicateProgram.SCOPE_LEFT
        : joinContext.localRole(scope) == 0;
  }
}
