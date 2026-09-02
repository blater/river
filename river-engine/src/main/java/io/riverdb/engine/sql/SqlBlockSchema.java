package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;

/** Compact typed output schema for one cardinality-changing query block. */
final class SqlBlockSchema {
  private final SqlBlockSchemaColumns columns;

  SqlBlockSchema() { this(new SqlSessionShapeBudget(null)); }
  SqlBlockSchema(SqlSessionShapeBudget budget) {
    columns = new SqlBlockSchemaColumns(budget);
  }

  void reset() {
    columns.reset();
  }

  void set(int columns) {
    this.columns.begin(columns);
  }

  void setColumn(int column, CharSequence name, int descriptor, boolean nullable) {
    columns.set(column, name, descriptor, nullable);
  }

  void setOutputNames(SqlCommand command) {
    for (int column = 0; column < columns.count(); column++) {
      columns.set(
          column,
          command.columnOutputName(column),
          columns.descriptor(column),
          columns.nullable(column));
    }
  }

  void copyFrom(SqlBlockSchema source) {
    set(source.count());
    for (int column = 0; column < columns.count(); column++) {
      setColumn(
          column, source.name(column), source.descriptor(column), source.nullable(column));
    }
  }

  io.riverdb.base.error.StatusCode status() { return columns.status(); }
  int count() { return columns.count(); }
  int descriptor(int column) { return columns.descriptor(column); }
  boolean nullable(int column) { return columns.nullable(column); }
  boolean varchar(int column) {
    return SqlTypeDescriptor.typeId(descriptor(column))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
  CharSequence name(int column) { return columns.name(column); }

  int find(CharSequence name) {
    int found = -1;
    for (int column = 0; column < columns.count(); column++) {
      if (!SqlBindingNames.same(columns.name(column), name)) continue;
      if (found >= 0) return -2;
      found = column;
    }
    return found;
  }
}
