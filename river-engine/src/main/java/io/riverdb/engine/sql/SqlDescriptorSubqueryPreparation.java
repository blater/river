package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;

/** Binds one descriptor subquery frame while its schema descriptor is pinned. */
final class SqlDescriptorSubqueryPreparation {
  StatusCode prepare(
      SqlDescriptorSubqueryFrameState state, SqlCommand child,
      SqlCommand outerCommand, TableDescriptor outer, int edgeKind,
      boolean edgeNegated, SqlComparison edgeComparison, int leftDescriptor) {
    state.command = child;
    state.kind = edgeKind;
    state.leftDescriptor = leftDescriptor;
    StatusCode status = state.session.resolveDescriptor(
        child.tableName(), state.pin, state.detail);
    if (!status.isOk()) return status;
    TableDescriptor table = state.pin.descriptor();
    status = state.values.reserve(table);
    if (status.isOk()) {
      status = state.predicate.prepare(child, table, outerCommand, outer);
    }
    if (status.isOk() && state.predicate.bindings().correlated()) {
      state.cache.markCorrelated(state.edge);
    }
    if (status.isOk()) {
      status = state.index.prepare(child, table, state.predicate.bindings());
    }
    if (status.isOk()) {
      state.plan.specializedAccess(
          state.edge, state.index.accessKind(), state.index.accessColumn());
      status = state.projection.prepare(child, table, edgeKind);
    }
    if (status.isOk()) status = configureOutcome(
        state, edgeKind, edgeNegated, edgeComparison, leftDescriptor);
    StatusCode released = state.pin.release();
    return status.isOk() ? released : status;
  }

  private StatusCode configureOutcome(
      SqlDescriptorSubqueryFrameState state, int edgeKind, boolean edgeNegated,
      SqlComparison edgeComparison, int leftDescriptor) {
    state.childDescriptor = state.projection.alwaysNull()
        ? leftDescriptor : state.projection.descriptor();
    if (edgeKind != io.riverdb.sql.SqlQuery.SUBQUERY_EXISTS) {
      if (!SqlTypeDescriptor.canCompare(leftDescriptor, state.childDescriptor)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      if (varchar(leftDescriptor) || varchar(state.childDescriptor)) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
    }
    state.outcome.configure(
        edgeKind, edgeNegated, edgeComparison, leftDescriptor, state.childDescriptor);
    return StatusCode.OK;
  }

  private static boolean varchar(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
}
