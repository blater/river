package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.relational.RelationalDescriptorJoinTableView;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.TableDescriptor;

/** Evaluates a commonly bound predicate over one reusable descriptor-row carrier. */
final class SqlDescriptorBoundPredicate {
  private final SqlBoundPredicateEvaluator evaluator;
  private final RelationalDescriptorJoinTableView bindingView =
      new RelationalDescriptorJoinTableView();
  private final SqlDescriptorBlockRowValues rows;
  private boolean active;

  SqlDescriptorBoundPredicate(SqlBoundPredicateEvaluator predicateEvaluator) {
    this(predicateEvaluator, null);
  }

  SqlDescriptorBoundPredicate(
      SqlBoundPredicateEvaluator predicateEvaluator, SqlSessionShapeBudget budget) {
    evaluator = predicateEvaluator;
    rows = budget == null
        ? new SqlDescriptorBlockRowValues() : new SqlDescriptorBlockRowValues(budget);
  }

  StatusCode prepareBinding(TableDescriptor table, TableDefinition target) {
    return bindingView.prepare(table, target);
  }

  StatusCode prepare(TableDescriptor table) {
    active = false;
    StatusCode status = rows.prepare(table, evaluator.program());
    if (status.isOk()) status = evaluator.prepare();
    if (status.isOk()) active = true;
    return status;
  }

  StatusCode evaluate(SqlValueBuffer values) {
    if (!active) return StatusCode.CONFLICT;
    StatusCode status = rows.load(values);
    return status.isOk() ? evaluator.evaluateBlock(rows.row()) : status;
  }

  boolean active() { return active; }
  boolean matched() { return evaluator.matched(); }

  void reset() {
    active = false;
    rows.reset();
  }
}
