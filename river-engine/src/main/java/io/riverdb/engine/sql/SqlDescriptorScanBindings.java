package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Binds descriptor consumers, subqueries, and predicate. */
final class SqlDescriptorScanBindings {
  private final SqlDescriptorScanContext context;

  SqlDescriptorScanBindings(SqlDescriptorScanContext owner) { context = owner; }

  StatusCode prepare(
      SqlCommand command, SqlQuery query, TableDescriptor table, SqlPhysicalPlan plan) {
    StatusCode status = context.scalarAggregate
        ? context.scalar.prepare(command, table, plan)
        : context.sets.handles(command)
            ? context.sets.prepare(command, table, plan)
            : context.projection.prepare(command, table);
    if (status.isOk()) status = context.subqueries.prepare(query, command, table);
    if (status.isOk()) {
      status = context.boundPredicate.active()
          ? context.predicate.prepareIndexCandidates(command, table)
          : context.predicate.prepare(command, table, context.subqueries);
    }
    return status;
  }
}
