package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Byte-addressed null bitmap access for transitional table-definition rows. */
final class SqlPhysicalRowNulls {
  private SqlPhysicalRowNulls() { }

  static void clear(ByteBuffer row, TableDefinition table) {
    int start = offset(table);
    int bytes = bytes(table);
    for (int index = 0; index < bytes; index++) row.put(start + index, (byte) 0);
  }

  static void set(ByteBuffer row, TableDefinition table, int column, boolean value) {
    int offset = offset(table) + (column >>> 3);
    int mask = 1 << (column & 7);
    int current = Byte.toUnsignedInt(row.get(offset));
    row.put(offset, (byte) (value ? current | mask : current & ~mask));
  }

  static boolean get(HeapRowResult row, TableDefinition table, int column) {
    int value = Byte.toUnsignedInt(row.getByte(offset(table) + (column >>> 3)));
    return (value & 1 << (column & 7)) != 0;
  }

  private static int offset(TableDefinition table) {
    return table.fixedRowBytes() - bytes(table);
  }

  private static int bytes(TableDefinition table) {
    return (table.columnCount() + 7) >>> 3;
  }
}
