package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorJoinTableView;
import io.riverdb.engine.relational.SqlValueAccess;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.TableDescriptor;

/** Evaluates a commonly bound predicate over one reusable descriptor-row carrier. */
final class SqlDescriptorBoundPredicate {
  private final SqlBoundPredicateEvaluator evaluator;
  private final RelationalDescriptorJoinTableView bindingView =
      new RelationalDescriptorJoinTableView();
  private boolean active;

  SqlDescriptorBoundPredicate(SqlBoundPredicateEvaluator predicateEvaluator) {
    evaluator = predicateEvaluator;
  }

  StatusCode prepareBinding(TableDescriptor table, TableDefinition target) {
    return bindingView.prepare(table, target);
  }

  StatusCode prepare() {
    active = false;
    StatusCode status = evaluator.prepare();
    if (status.isOk()) active = true;
    return status;
  }

  StatusCode evaluate(SqlValueAccess values) {
    if (!active) return StatusCode.CONFLICT;
    return evaluator.evaluateDescriptor(values);
  }

  boolean active() { return active; }
  boolean matched() { return evaluator.matched(); }

  void reset() {
    active = false;
  }
}
