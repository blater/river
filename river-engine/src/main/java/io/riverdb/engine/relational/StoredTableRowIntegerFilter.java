package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlComparison;
import java.nio.ByteBuffer;

/** One retained integer comparison over a bounded fixed row prefix. */
public final class StoredTableRowIntegerFilter {
  private int column = -1;
  private SqlComparison comparison;
  private long literal;

  public StatusCode configure(
      int selectedColumn, SqlComparison selectedComparison, long value) {
    reset();
    if (selectedColumn < 0 || !supported(selectedComparison)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    column = selectedColumn;
    comparison = selectedComparison;
    literal = value;
    return StatusCode.OK;
  }

  public void reset() {
    column = -1;
    comparison = null;
    literal = 0;
  }

  public boolean active() { return column >= 0; }

  StatusCode test(TableDescriptor table, ByteBuffer source, int start) {
    int selectedColumn = column;
    SqlComparison selectedComparison = comparison;
    long selectedLiteral = literal;
    if (table == null || selectedColumn < 0 || selectedColumn >= table.columnCount()
        || !supported(selectedComparison)
        || !integer(table.typeDescriptorAt(selectedColumn))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (StoredTableRowAccess.nullAt(source, start, selectedColumn)) {
      return StatusCode.CONFLICT;
    }
    int slot = start + table.fixedOffsetAt(selectedColumn);
    long value = StoredTableRowAccess.fixedValue(table, selectedColumn, source, slot);
    boolean matches = switch (selectedComparison) {
      case EQUAL -> value == selectedLiteral;
      case NOT_EQUAL -> value != selectedLiteral;
      case LESS_THAN -> value < selectedLiteral;
      case LESS_OR_EQUAL -> value <= selectedLiteral;
      case GREATER_THAN -> value > selectedLiteral;
      case GREATER_OR_EQUAL -> value >= selectedLiteral;
      default -> false;
    };
    return matches ? StatusCode.OK : StatusCode.CONFLICT;
  }

  private static boolean supported(SqlComparison candidate) {
    return candidate == SqlComparison.EQUAL
        || candidate == SqlComparison.NOT_EQUAL
        || candidate == SqlComparison.LESS_THAN
        || candidate == SqlComparison.LESS_OR_EQUAL
        || candidate == SqlComparison.GREATER_THAN
        || candidate == SqlComparison.GREATER_OR_EQUAL;
  }

  private static boolean integer(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_SMALLINT
        || type == SqlTypeDescriptor.TYPE_ID_INTEGER
        || type == SqlTypeDescriptor.TYPE_ID_BIGINT;
  }
}
