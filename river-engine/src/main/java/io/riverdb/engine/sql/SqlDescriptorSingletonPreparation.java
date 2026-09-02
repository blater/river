package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Prepares and opens one cardinality-safe descriptor point scan. */
final class SqlDescriptorSingletonPreparation {
  private final SqlDescriptorMutationValues values;
  private final SqlDescriptorProjection projection;
  private final SqlDescriptorPredicate predicate;
  private final SqlDescriptorBoundPredicate boundPredicate;
  private final SqlDescriptorPointScanAccess access;

  SqlDescriptorSingletonPreparation(
      SqlDescriptorMutationValues mutationValues,
      SqlDescriptorProjection resultProjection,
      SqlDescriptorPredicate rowPredicate,
      SqlDescriptorBoundPredicate expressionPredicate,
      SqlDescriptorPointScanAccess scanAccess) {
    values = mutationValues;
    projection = resultProjection;
    predicate = rowPredicate;
    boundPredicate = expressionPredicate;
    access = scanAccess;
  }

  StatusCode open(SqlCommand command, SchemaPin pin, TableDescriptor table) {
    StatusCode status = values.reserve(table);
    if (status.isOk()) status = projection.prepare(command, table);
    if (status.isOk()) status = preparePredicate(command, table);
    if (status.isOk()) status = access.prepare(command, table, predicate);
    if (status.isOk() && command.isSelectForUpdate() && !access.exactUnique()) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status.isOk() ? access.open(pin) : status;
  }

  private StatusCode preparePredicate(SqlCommand command, TableDescriptor table) {
    return boundPredicate.active()
        ? predicate.prepareIndexCandidates(command, table)
        : predicate.prepare(command, table);
  }
}
