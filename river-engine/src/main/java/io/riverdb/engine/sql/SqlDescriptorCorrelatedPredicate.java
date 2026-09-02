package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;

/** Bound fixed-value/column predicate for one descriptor subquery frame. */
final class SqlDescriptorCorrelatedPredicate {
  private final SqlDescriptorCorrelatedBindings bindings;
  private final SqlDescriptorCorrelatedEvaluation evaluation =
      new SqlDescriptorCorrelatedEvaluation();
  private SqlBooleanPredicateProgram program;
  private int truth;

  SqlDescriptorCorrelatedPredicate() {
    this(null);
  }

  SqlDescriptorCorrelatedPredicate(SqlSessionShapeBudget budget) {
    bindings = budget == null
        ? new SqlDescriptorCorrelatedBindings()
        : new SqlDescriptorCorrelatedBindings(budget);
  }

  StatusCode prepare(
      SqlCommand command,
      TableDescriptor child,
      SqlCommand outerCommand,
      TableDescriptor outer) {
    program = command.wherePredicates();
    StatusCode status = bindings.prepare(
        command, child, outerCommand, outer, program);
    return status.isOk()
        ? SqlDescriptorCorrelatedMembership.validate(program, bindings) : status;
  }

  StatusCode evaluate(
      SqlDescriptorValueSource child, SqlDescriptorValueSource outer) {
    truth = evaluation.evaluate(program, bindings, child, outer);
    return StatusCode.OK;
  }

  boolean matched() { return truth == 1; }
  SqlDescriptorCorrelatedBindings bindings() { return bindings; }

  void reset() {
    program = null;
    truth = 0;
  }

}
