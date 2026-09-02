package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Routes descriptor point fallbacks through one shared retryable scan owner. */
final class SqlDescriptorPointScanExecution {
  private final SqlDescriptorPointScanAccess access;
  private final SqlDescriptorSingletonScan selects;
  private final SqlDescriptorUpdateScan updates;
  private final SqlDescriptorDeleteScan deletes;
  private int affectedRows;

  SqlDescriptorPointScanExecution(
      RelationalSession session, SqlDescriptorMutationValues values,
      SqlDescriptorColumnMapping columns, SqlDescriptorProjection projection,
      SqlDescriptorPredicate predicate, SqlDescriptorBoundPredicate boundPredicate,
      SqlRowProjectionEvaluator expressions) {
    access = new SqlDescriptorPointScanAccess(session);
    selects = new SqlDescriptorSingletonScan(
        session, values, projection, predicate, boundPredicate, access);
    updates = new SqlDescriptorUpdateScan(
        values, columns, predicate, boundPredicate, expressions, access);
    deletes = new SqlDescriptorDeleteScan(values, predicate, boundPredicate, access);
  }

  StatusCode select(SqlCommand command, SchemaPin pin, SqlExecutionResult result) {
    affectedRows = 0;
    return selects.execute(command, pin, result);
  }

  StatusCode update(SqlCommand command, SchemaPin pin) {
    StatusCode status = updates.execute(command, pin);
    affectedRows = updates.affectedRows();
    return status;
  }

  StatusCode delete(SqlCommand command, SchemaPin pin) {
    StatusCode status = deletes.execute(command, pin);
    affectedRows = deletes.affectedRows();
    return status;
  }

  int affectedRows() { return affectedRows; }
  boolean hasResources() { return access.active(); }
  StatusCode close() { return access.close(); }
}
