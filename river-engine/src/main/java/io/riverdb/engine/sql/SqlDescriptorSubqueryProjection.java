package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Bound direct scalar output for one descriptor subquery. */
final class SqlDescriptorSubqueryProjection {
  private int column;
  private int descriptor;
  private boolean nullProjection;

  StatusCode prepare(SqlCommand command, TableDescriptor table, int kind) {
    column = -1;
    descriptor = 0;
    nullProjection = false;
    if (kind == SqlQuery.SUBQUERY_EXISTS && command.isSelectAll()) {
      return StatusCode.OK;
    }
    if (command.columnCount() != 1 || command.isSelectAll()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.isNullProjection(0)) {
      nullProjection = true;
      return StatusCode.OK;
    }
    int symbol = command.directProjectionSymbol(0);
    if (symbol < 0) return StatusCode.FEATURE_NOT_SUPPORTED;
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    if (qualifier.length() != 0
        && !SqlDescriptorPrimaryPredicate.same(qualifier, command.tableName())
        && !(command.tableAlias().length() > 0
            && SqlDescriptorPrimaryPredicate.same(qualifier, command.tableAlias()))) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    column = table.findColumn(command.projectionSymbolName(symbol));
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    descriptor = table.typeDescriptorAt(column);
    return kind == SqlQuery.SUBQUERY_EXISTS
        ? StatusCode.OK : SqlTypeDescriptor.typeId(descriptor)
        == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
  }

  boolean isNull(SqlValueBuffer values) {
    return nullProjection || column >= 0 && values.isNull(column);
  }

  long value(SqlValueBuffer values) {
    return column < 0 ? 0 : values.valueAt(column);
  }

  long highValue(SqlValueBuffer values) {
    return column < 0 ? 0 : values.highValueAt(column);
  }

  boolean alwaysNull() { return nullProjection; }

  int descriptor() { return descriptor; }
}
