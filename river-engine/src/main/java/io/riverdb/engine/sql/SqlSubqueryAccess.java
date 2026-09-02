package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlQuery;
import io.riverdb.sql.SqlScalarExpression;

/** Selects and opens one residual-rechecked physical edge per subquery block. */
final class SqlSubqueryAccess {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlPredicateOperandEvaluator values;
  private final SqlPredicateOperand value = new SqlPredicateOperand();
  private final int[] columns = new int[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final int[] leaves = new int[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final byte[] programs = new byte[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlComparison[] comparisons =
      new SqlComparison[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final boolean[] valueIndexes = new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final boolean[] empty = new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];

  SqlSubqueryAccess(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporal) {
    session = relationalSession;
    bound = statement;
    query = statement.executableQuery;
    values = new SqlPredicateOperandEvaluator(evaluator, temporal);
    clear();
  }

  void prepare() {
    clear();
    int root = query.sourceBlockCount() - 1;
    for (int block = root + 1; block < query.blockCount(); block++) {
      select(block, bound.nestedBoolean(block));
    }
  }

  StatusCode begin(
      int block,
      SqlNestedRowProvider rows,
      RelationalScanCursor cursor) {
    empty[block] = false;
    int column = columns[block];
    TableDefinition table = query.block(block).table();
    if (column < 0) return session.beginScan(table, cursor);
    StatusCode status = values.evaluateNested(
        bound.query.block(block),
        bound.nestedBoolean(block),
        leaves[block],
        Byte.toUnsignedInt(programs[block]),
        null,
        rows,
        value);
    if (!status.isOk()) return status;
    if (value.nullValue()) {
      empty[block] = true;
      return StatusCode.OK;
    }
    long candidate = value.value();
    SqlComparison comparison = comparisons[block];
    if (comparison == SqlComparison.EQUAL) {
      return column == 0
          ? session.beginExactScan(table, candidate, cursor)
          : session.beginExactValueScan(table, column, candidate, cursor);
    }
    int descriptor = table.typeDescriptor(column);
    long lower = lower(comparison, candidate, table.typeDescriptor(column));
    long upper = upper(comparison, candidate, table.typeDescriptor(column));
    if (lower >= upper) {
      empty[block] = true;
      return StatusCode.OK;
    }
    return column == 0
        ? session.beginScan(table, lower, upper, cursor)
        : session.beginValueScan(table, column, lower, upper, cursor);
  }

  StatusCode next(
      int block,
      RelationalScanCursor cursor,
      RelationalScanResult scan,
      ValueIndexLookupResult indexed) {
    if (empty[block]) return StatusCode.CONFLICT;
    return valueIndexes[block]
        ? session.nextValueScan(query.block(block).table(), cursor, scan, indexed)
        : session.nextScan(cursor, scan);
  }

  boolean indexed(int block) { return columns[block] >= 0; }
  boolean valueIndex(int block) { return valueIndexes[block]; }
  int column(int block) { return columns[block]; }

  private void select(int block, SqlBoundBooleanPredicateProgram predicates) {
    if (query.block(block).descriptorRole(0)
        || query.block(block).joinChain() != null
        || predicates == null || !predicates.available()) return;
    collect(block, predicates, predicates.root());
  }

  private void collect(
      int block, SqlBoundBooleanPredicateProgram predicates, int node) {
    int operator = predicates.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      collect(block, predicates, predicates.booleanLeft(node));
      collect(block, predicates, predicates.booleanRight(node));
    } else if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      candidate(block, predicates, predicates.booleanLeft(node));
    }
  }

  private void candidate(
      int block, SqlBoundBooleanPredicateProgram predicates, int leaf) {
    if (predicates.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON
        || predicates.negated(leaf)) return;
    SqlComparison comparison = predicates.comparison(leaf);
    int local = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    int source = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
    int column = predicates.rawColumn(leaf, local);
    if (column < 0 || predicates.scope(leaf, local, 0) != block) {
      local = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
      source = SqlBooleanPredicateProgram.PROGRAM_LEFT;
      column = predicates.rawColumn(leaf, local);
      comparison = reverse(comparison);
    }
    if (!stable(comparison)) return;
    if (!usable(block, predicates, leaf, local, source, column)) return;
    if (comparison == SqlComparison.GREATER_OR_EQUAL
        && unboundedUpper(query.block(block).table().typeDescriptor(column))) return;
    int score = score(query.block(block).table(), column, comparison);
    int previous = columns[block] < 0 ? -1
        : score(query.block(block).table(), columns[block], comparisons[block]);
    if (score <= previous) return;
    columns[block] = column;
    leaves[block] = leaf;
    programs[block] = (byte) source;
    comparisons[block] = comparison;
    valueIndexes[block] = column > 0;
  }

  private boolean usable(
      int block,
      SqlBoundBooleanPredicateProgram predicates,
      int leaf,
      int local,
      int source,
      int column) {
    if (column < 0
        || predicates.nodeCount(leaf, local) != 1
        || predicates.nodeCount(leaf, source) != 1
        || predicates.operator(leaf, local, 0) != SqlScalarExpression.COLUMN
        || predicates.operator(leaf, source, 0) != SqlScalarExpression.COLUMN) {
      return false;
    }
    int localScope = predicates.scope(leaf, local, 0);
    if (SqlNestedRowProvider.block(localScope) != block
        || SqlNestedRowProvider.role(localScope) != 0) return false;
    TableDefinition table = query.block(block).table();
    if (column > 0 && !table.hasIndexOn(column)) return false;
    int descriptor = table.typeDescriptor(column);
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        || predicates.resultDescriptor(leaf, local) != descriptor
        || predicates.resultDescriptor(leaf, source) != descriptor) return false;
    int scope = predicates.scope(leaf, source, 0);
    return ancestor(block, SqlNestedRowProvider.block(scope));
  }

  private boolean ancestor(int block, int candidate) {
    int parent = query.blockParent(block);
    while (parent >= 0 && parent != candidate) parent = query.blockParent(parent);
    return parent == candidate;
  }

  private static int score(
      TableDefinition table, int column, SqlComparison comparison) {
    int score = comparison == SqlComparison.EQUAL ? 2 : 0;
    return score + (column == 0 || table.hasUniqueIndexOn(column) ? 2 : 1);
  }

  private static long lower(SqlComparison comparison, long value, int descriptor) {
    if (comparison == SqlComparison.GREATER_OR_EQUAL) return value;
    return SqlValueDomain.minimumFixed(descriptor);
  }

  private static long upper(SqlComparison comparison, long value, int descriptor) {
    if (comparison == SqlComparison.LESS_THAN) return value;
    return SqlValueDomain.exclusiveMaximumFixed(descriptor);
  }

  private static boolean unboundedUpper(int descriptor) {
    return SqlValueDomain.exclusiveMaximumFixed(descriptor) == Long.MIN_VALUE;
  }

  private static boolean stable(SqlComparison comparison) {
    return comparison == SqlComparison.EQUAL
        || comparison == SqlComparison.LESS_THAN
        || comparison == SqlComparison.GREATER_OR_EQUAL;
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

  private void clear() {
    values.reset();
    value.clear();
    for (int block = 0; block < columns.length; block++) {
      columns[block] = -1;
      leaves[block] = -1;
      programs[block] = 0;
      comparisons[block] = null;
      valueIndexes[block] = false;
      empty[block] = false;
    }
  }
}
