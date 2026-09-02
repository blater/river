package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Transactionally retained direct-column predicate bindings. */
final class SqlDescriptorPredicateBindings implements SqlDescriptorIndexCandidateSource {
  private final SqlDescriptorPredicateBindingArrays values;
  private final SqlDescriptorPredicateSpecialBinding special =
      new SqlDescriptorPredicateSpecialBinding(this);
  private int count;
  private SqlCommand command;
  private SqlBooleanPredicateProgram program;

  SqlDescriptorPredicateBindings(SqlRetainedArrayAllocator arrayAllocator) {
    values = new SqlDescriptorPredicateBindingArrays(arrayAllocator);
  }

  StatusCode prepare(
      SqlCommand command, TableDescriptor table,
      SqlBooleanPredicateProgram program, boolean subqueries) {
    int count = program.isAvailable() ? program.leafCount() : 0;
    this.count = count;
    this.command = command;
    this.program = program;
    StatusCode status = reserve(count);
    for (int leaf = 0; status.isOk() && leaf < count; leaf++) {
      status = bindLeaf(command, table, program, leaf, subqueries);
    }
    return status;
  }

  StatusCode prepareIndexCandidates(
      SqlCommand command, TableDescriptor table, SqlBooleanPredicateProgram program) {
    int count = program.isAvailable() ? program.leafCount() : 0;
    this.count = count;
    this.command = command;
    this.program = program;
    StatusCode status = reserve(count);
    for (int leaf = 0; status.isOk() && leaf < count; leaf++) {
      values.clear(leaf);
      status = bindLeaf(command, table, program, leaf, false);
      if (status == StatusCode.FEATURE_NOT_SUPPORTED) status = StatusCode.OK;
    }
    return status;
  }

  int column(int leaf) { return values.column(leaf); }
  int descriptor(int leaf) { return values.descriptor(leaf); }
  int descriptor(int leaf, boolean upper) {
    return between(leaf)
        ? program.programDescriptor(
            leaf, upper ? SqlBooleanPredicateProgram.PROGRAM_UPPER
                : SqlBooleanPredicateProgram.PROGRAM_LOWER, 0)
        : values.descriptor(leaf);
  }
  int columnDescriptor(int leaf) { return values.columnDescriptor(leaf); }
  long literal(int leaf) { return values.literal(leaf); }
  long literal(int leaf, boolean upper) {
    return between(leaf)
        ? program.programOperand(
            leaf, upper ? SqlBooleanPredicateProgram.PROGRAM_UPPER
                : SqlBooleanPredicateProgram.PROGRAM_LOWER, 0)
        : values.literal(leaf);
  }
  long literalHigh(int leaf) { return values.literalHigh(leaf); }
  long literalHigh(int leaf, boolean upper) {
    return between(leaf)
        ? program.programOperandHigh(
            leaf, upper ? SqlBooleanPredicateProgram.PROGRAM_UPPER
                : SqlBooleanPredicateProgram.PROGRAM_LOWER, 0)
        : values.literalHigh(leaf);
  }
  SqlComparison comparison(int leaf) { return values.comparison(leaf); }
  boolean indexBound(int leaf) {
    return program != null
        && leaf >= 0
        && leaf < count
        && (program.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_COMPARISON
            || between(leaf));
  }
  boolean matches(int leaf, SqlComparison comparison) {
    return between(leaf)
        ? comparison == SqlComparison.GREATER_OR_EQUAL
            || comparison == SqlComparison.LESS_OR_EQUAL
        : values.comparison(leaf) == comparison;
  }

  @Override public int find(int column, SqlComparison comparison) {
    for (int leaf = 0; leaf < count; leaf++) {
      if (indexBound(leaf) && column(leaf) == column && matches(leaf, comparison)) return leaf;
    }
    return -1;
  }
  boolean between(int leaf) {
    return program != null && leaf >= 0 && leaf < count
        && program.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_BETWEEN
        && !program.leafNegated(leaf);
  }
  SqlCommand command() { return command; }
  int count() { return count; }

  void reset() {
    count = 0;
    command = null;
    program = null;
  }

  StatusCode reserve(int count) {
    return values.reserve(count);
  }

  StatusCode bindSubqueryExists(int leaf) {
    values.column(leaf, -1);
    return StatusCode.OK;
  }

  private StatusCode bindLeaf(
      SqlCommand command, TableDescriptor table,
      SqlBooleanPredicateProgram program, int leaf, boolean subqueries) {
    int test = program.leafTest(leaf);
    if (test != SqlBooleanPredicateProgram.TEST_COMPARISON) {
      return special.bind(command, table, program, leaf, test, subqueries);
    }
    StatusCode status = bindPrograms(command, table, program, leaf,
        SqlBooleanPredicateProgram.PROGRAM_LEFT,
        SqlBooleanPredicateProgram.PROGRAM_RIGHT, false);
    if (status != StatusCode.FEATURE_NOT_SUPPORTED) return status;
    return status.isOk() ? status : bindPrograms(command, table, program, leaf,
        SqlBooleanPredicateProgram.PROGRAM_RIGHT,
        SqlBooleanPredicateProgram.PROGRAM_LEFT, true);
  }

  StatusCode bindColumn(
      SqlCommand command, TableDescriptor table,
      SqlBooleanPredicateProgram program, int leaf) {
    int side = SqlBooleanPredicateProgram.PROGRAM_LEFT;
    if (program.programNodeCount(leaf, side) != 1
        || program.programOperator(leaf, side, 0) != SqlScalarExpression.COLUMN) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int symbol = (int) program.programOperand(leaf, side, 0);
    int column = table.findColumn(command.predicateSymbolName(symbol));
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    values.column(leaf, column);
    values.descriptor(leaf, table.typeDescriptorAt(column));
    values.columnDescriptor(leaf, table.typeDescriptorAt(column));
    values.comparison(leaf, program.comparison(leaf));
    return StatusCode.OK;
  }

  private StatusCode bindPrograms(
      SqlCommand command, TableDescriptor table, SqlBooleanPredicateProgram program,
      int leaf, int columnProgram, int literalProgram, boolean reversed) {
    if (program.programNodeCount(leaf, columnProgram) != 1
        || program.programOperator(leaf, columnProgram, 0) != SqlScalarExpression.COLUMN
        || program.programNodeCount(leaf, literalProgram) != 1
        || program.programOperator(leaf, literalProgram, 0) != SqlScalarExpression.LITERAL) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int symbol = (int) program.programOperand(leaf, columnProgram, 0);
    int column = table.findColumn(command.predicateSymbolName(symbol));
    int literalType = program.programDescriptor(leaf, literalProgram, 0);
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!SqlTypeDescriptor.canCompare(table.typeDescriptorAt(column), literalType)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    values.column(leaf, column);
    values.descriptor(leaf, literalType);
    values.columnDescriptor(leaf, table.typeDescriptorAt(column));
    values.literal(leaf, program.programOperand(leaf, literalProgram, 0));
    values.literalHigh(leaf, program.programOperandHigh(leaf, literalProgram, 0));
    values.comparison(leaf, reverse(program.comparison(leaf), reversed));
    if (values.comparison(leaf) == null) return StatusCode.FEATURE_NOT_SUPPORTED;
    if (SqlTypeDescriptor.typeId(values.columnDescriptor(leaf))
            == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        && values.comparison(leaf) != SqlComparison.EQUAL
        && values.comparison(leaf) != SqlComparison.NOT_EQUAL) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return StatusCode.OK;
  }

  private static SqlComparison reverse(SqlComparison comparison, boolean reversed) {
    if (!reversed) return comparison;
    return switch (comparison) {
      case LESS_THAN -> SqlComparison.GREATER_THAN;
      case LESS_OR_EQUAL -> SqlComparison.GREATER_OR_EQUAL;
      case GREATER_THAN -> SqlComparison.LESS_THAN;
      case GREATER_OR_EQUAL -> SqlComparison.LESS_OR_EQUAL;
      case EQUAL, NOT_EQUAL -> comparison;
      default -> null;
    };
  }
}
