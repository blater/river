package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Binds non-comparison descriptor predicate leaves to direct columns. */
final class SqlDescriptorPredicateSpecialBinding {
  private final SqlDescriptorPredicateBindings bindings;

  SqlDescriptorPredicateSpecialBinding(SqlDescriptorPredicateBindings owner) {
    bindings = owner;
  }

  StatusCode bind(
      SqlCommand command,
      TableDescriptor table,
      SqlBooleanPredicateProgram program,
      int leaf,
      int test,
      boolean subqueries) {
    if (subquery(test)) return bindSubquery(command, table, program, leaf, test, subqueries);
    StatusCode status = bindings.bindColumn(command, table, program, leaf);
    if (!status.isOk()) return status;
    if (test == SqlBooleanPredicateProgram.TEST_NULL) return status;
    if (test == SqlBooleanPredicateProgram.TEST_TRUTH
        || test == SqlBooleanPredicateProgram.TEST_BOOLEAN) return booleanColumn(leaf);
    if (test == SqlBooleanPredicateProgram.TEST_MEMBERSHIP) {
      return membership(program, leaf);
    }
    return test == SqlBooleanPredicateProgram.TEST_BETWEEN
        ? between(table, program, leaf) : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private StatusCode bindSubquery(
      SqlCommand command,
      TableDescriptor table,
      SqlBooleanPredicateProgram program,
      int leaf,
      int test,
      boolean enabled) {
    if (!enabled) return StatusCode.FEATURE_NOT_SUPPORTED;
    return test == SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS
        ? bindings.bindSubqueryExists(leaf)
        : bindings.bindColumn(command, table, program, leaf);
  }

  private StatusCode booleanColumn(int leaf) {
    return SqlTypeDescriptor.typeId(bindings.columnDescriptor(leaf))
            == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  private StatusCode membership(SqlBooleanPredicateProgram program, int leaf) {
    for (int member = 0; member < program.leafMemberCount(leaf); member++) {
      int descriptor = program.memberDescriptor(leaf, member);
      if (descriptor != 0 && !SqlTypeDescriptor.canCompare(
          bindings.columnDescriptor(leaf), descriptor)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode between(
      TableDescriptor table, SqlBooleanPredicateProgram program, int leaf) {
    StatusCode status = literal(table, program, leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER);
    return status.isOk()
        ? literal(table, program, leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER) : status;
  }

  private StatusCode literal(
      TableDescriptor table, SqlBooleanPredicateProgram program, int leaf, int side) {
    if (program.programNodeCount(leaf, side) != 1
        || program.programOperator(leaf, side, 0) != SqlScalarExpression.LITERAL) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int descriptor = program.programDescriptor(leaf, side, 0);
    return descriptor == 0
            || SqlTypeDescriptor.canCompare(
                table.typeDescriptorAt(bindings.column(leaf)), descriptor)
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  private static boolean subquery(int test) {
    return test >= SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS
        && test <= SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP;
  }
}
