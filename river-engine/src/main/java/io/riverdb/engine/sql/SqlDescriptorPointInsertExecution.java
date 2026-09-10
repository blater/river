package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorBatchInsert;
import io.riverdb.engine.relational.RelationalDescriptorInsertBatch;
import io.riverdb.engine.relational.RelationalRowIdentityResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Executes descriptor inserts while retaining all row-path working state. */
final class SqlDescriptorPointInsertExecution {
  private final SqlDescriptorColumnMapping columns;
  private final SqlDescriptorMutationValues values;
  private final RelationalDescriptorBatchInsert inserts;
  private final SqlRowProjectionEvaluator expressions;
  private final RelationalDescriptorInsertBatch batch;
  private final RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
  private int affectedRows;

  SqlDescriptorPointInsertExecution(
      RelationalSession session, SqlDescriptorColumnMapping columnMapping,
      SqlDescriptorMutationValues mutationValues,
      SqlRowProjectionEvaluator expressionEvaluator) {
    columns = columnMapping;
    values = mutationValues;
    expressions = expressionEvaluator;
    batch = new RelationalDescriptorInsertBatch();
    inserts = session.descriptorRows().batchInsert();
  }

  StatusCode execute(SqlCommand command, SchemaPin pin) {
    affectedRows = 0;
    TableDescriptor table = pin.descriptor();
    StatusCode status = values.reserve(table);
    if (status.isOk()) status = columns.mapInsert(command, table);
    if (status.isOk()) status = inserts.begin(batch, pin, command.insertRowCount());
    for (int row = 0; status.isOk() && row < command.insertRowCount(); row++) {
      status = values.buildInsert(command, table, columns, expressions, row);
      if (status.isOk()) status = inserts.insert(
          batch, pin, values.mutation(), identity);
      if (status.isOk()) affectedRows++;
    }
    if (status.isOk() && table.foreignKeyCount() > 0
        && command.insertRowCount() > 1) {
      for (int row = 0; status.isOk() && row < command.insertRowCount(); row++) {
        status = inserts.validateForeignKeys(batch, pin, row, values.mutation());
      }
    }
    batch.reset();
    return status;
  }

  int affectedRows() { return affectedRows; }
}
