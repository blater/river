package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;

/** Pre-scan schema, decoded-row, and physical-row admission for merge input. */
final class SqlJoinMergeAdmission {
  private SqlJoinMergeAdmission() { }

  static StatusCode prepare(
      TableDefinition definition,
      SqlBlockSchema schema,
      SqlBlockPhysicalRowReader reader,
      SqlBlockRow right,
      SqlBlockRow candidate,
      SqlBlockPhysicalRowWriter writer) {
    schema.set(definition.columnCount());
    StatusCode status = schema.status();
    if (status.isOk()) status = reader.prepare(definition, right);
    if (status.isOk()) status = reader.prepare(definition, candidate);
    if (status.isOk()) status = writer.prepare();
    if (!status.isOk()) return status;
    for (int column = 0; column < definition.columnCount(); column++) {
      schema.setColumn(column, definition.columnName(column),
          definition.typeDescriptor(column), definition.isNullable(column));
    }
    return schema.status();
  }
}
