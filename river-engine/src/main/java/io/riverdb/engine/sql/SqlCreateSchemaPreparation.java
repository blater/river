package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import java.nio.ByteBuffer;

/** Builds a bounded table schema, including defaults and check programs. */
final class SqlCreateSchemaPreparation {
  private SqlCreateSchemaPreparation() { }

  static StatusCode prepare(
      SqlCommand command,
      TableSchema schema,
      ByteBuffer row,
      SqlCheckExpressionBinder checks,
      SqlExpressionEvaluator expressions) {
    schema.reset();
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < command.columnCount(); index++) {
      status = schema.addColumn(
          command.columnName(index),
          command.columnTypeDescriptor(index),
          !command.columnIsNotNull(index));
      if (status.isOk() && command.columnHasDefault(index)) {
        status = addDefault(command, schema, row, index);
      }
    }
    for (int index = 0; status.isOk() && index < command.columnCount(); index++) {
      if (command.columnHasCheck(index)) {
        status = checks.bind(
            command,
            schema,
            index,
            expressions.checkComparisonCode(command.columnCheckComparison(index)));
      }
    }
    if (status.isOk() && command.hasPrimaryKeyIdentity()) {
      status = schema.setPrimaryKeyIdentity();
    }
    return status;
  }

  private static StatusCode addDefault(
      SqlCommand command,
      TableSchema schema,
      ByteBuffer row,
      int index) {
    if (SqlDefaultKind.isCurrent(command.columnDefaultKind(index))) {
      return schema.setLastCurrentDefault(command.columnDefaultKind(index));
    }
    if (!command.columnIsVarchar(index)) {
      return schema.setLastDefault(command.columnDefaultValue(index));
    }
    row.clear();
    int bytes = command.copyText(command.columnDefaultValue(index), row);
    if (bytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    row.flip();
    return schema.setLastTextDefault(row);
  }
}
