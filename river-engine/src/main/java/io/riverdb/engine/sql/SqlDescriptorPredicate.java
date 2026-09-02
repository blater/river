package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;

/** Bound direct-column fixed-value predicate for descriptor scans. */
final class SqlDescriptorPredicate {
  private final SqlDescriptorValueSource source = new SqlDescriptorValueSource();
  private final SqlDescriptorPredicateBindings bindings;
  private final SqlDescriptorPredicateEvaluation evaluation =
      new SqlDescriptorPredicateEvaluation();
  private final SqlDescriptorPredicateEvaluation.Match match =
      new SqlDescriptorPredicateEvaluation.Match();

  SqlDescriptorPredicate() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlDescriptorPredicate(SqlRetainedArrayAllocator allocator) {
    bindings = new SqlDescriptorPredicateBindings(allocator);
  }

  StatusCode prepare(SqlCommand command, TableDescriptor table) {
    return prepare(command, table, null);
  }

  StatusCode prepare(
      SqlCommand command,
      TableDescriptor table,
      SqlDescriptorSubqueryExecution subqueries) {
    SqlBooleanPredicateProgram program = command.wherePredicates();
    StatusCode status = bindings.prepare(
        command, table, program, subqueries != null);
    if (status.isOk()) evaluation.prepare(program, bindings, subqueries);
    return status;
  }

  StatusCode evaluate(SqlValueBuffer values) {
    return evaluation.evaluate(source.use(values), match);
  }

  StatusCode evaluate(SqlBlockRow values) {
    return evaluation.evaluate(source.use(values), match);
  }

  StatusCode prepareIndexCandidates(SqlCommand command, TableDescriptor table) {
    return bindings.prepareIndexCandidates(command, table, command.wherePredicates());
  }

  boolean matched() { return match.value(); }
  StatusCode reserve(int count) { return bindings.reserve(count); }
  SqlDescriptorPredicateBindings bindings() { return bindings; }
  void reset() { bindings.reset(); }
}
