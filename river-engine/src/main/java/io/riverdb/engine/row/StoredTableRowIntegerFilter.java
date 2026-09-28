package io.riverdb.engine.row;

import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlComparison;
import java.nio.ByteBuffer;

/** One retained integer comparison over a bounded fixed row prefix. */
public final class StoredTableRowIntegerFilter implements StoredTableRowFilter {
  private int column = -1;
  private SqlComparison comparison;
  private long literal;

  public void configure(int selectedColumn, SqlComparison selectedComparison, long value) {
    column = selectedColumn;
    comparison = selectedComparison;
    literal = value;
  }

  public void reset() {
    column = -1;
    comparison = null;
    literal = 0;
  }

  public boolean active() { return column >= 0; }

  @Override public boolean matches(TableDescriptor table, ByteBuffer source, int start) {
    if (StoredTableRowAccess.nullAt(source, start, column)) return false;
    int slot = start + table.fixedOffsetAt(column);
    long value = StoredTableRowAccess.fixedValue(table, column, source, slot);
    return switch (comparison) {
      case EQUAL -> value == literal;
      case NOT_EQUAL -> value != literal;
      case LESS_THAN -> value < literal;
      case LESS_OR_EQUAL -> value <= literal;
      case GREATER_THAN -> value > literal;
      case GREATER_OR_EQUAL -> value >= literal;
      default -> true;
    };
  }
}
