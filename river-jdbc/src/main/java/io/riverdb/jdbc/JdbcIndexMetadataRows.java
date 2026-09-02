package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import java.sql.SQLException;
import java.util.Objects;

/** Geometrically retained and sortable JDBC index-part rows. */
final class JdbcIndexMetadataRows {
  private static final int MAXIMUM_ROWS =
      SqlShapeLimits.MAX_TABLE_INDEXES * SqlShapeLimits.MAX_KEY_PARTS;
  private String[] names = new String[8];
  private String[] columns = new String[8];
  private boolean[] unique = new boolean[8];
  private short[] ordinals = new short[8];

  void append(int index, String name, String column, boolean isUnique) throws SQLException {
    reserve(index + 1);
    int ordinal = 1;
    for (int prior = 0; prior < index; prior++) {
      if (Objects.equals(name, names[prior])) ordinal++;
    }
    if (ordinal > SqlShapeLimits.MAX_KEY_PARTS) {
      throw JdbcExceptions.failure(StatusCode.CORRUPTION, "decode index key parts");
    }
    names[index] = name;
    columns[index] = column;
    unique[index] = isUnique;
    ordinals[index] = (short) ordinal;
  }

  int compare(int left, int right) {
    int uniqueness = Boolean.compare(unique[right], unique[left]);
    if (uniqueness != 0) return uniqueness;
    int compared = names[left] == null
        ? names[right] == null ? 0 : -1
        : names[right] == null ? 1 : names[left].compareTo(names[right]);
    return compared != 0 ? compared : Short.compare(ordinals[left], ordinals[right]);
  }

  void swap(int left, int right) {
    String name = names[left]; names[left] = names[right]; names[right] = name;
    String column = columns[left]; columns[left] = columns[right]; columns[right] = column;
    boolean isUnique = unique[left]; unique[left] = unique[right]; unique[right] = isUnique;
    short ordinal = ordinals[left]; ordinals[left] = ordinals[right]; ordinals[right] = ordinal;
  }

  String name(int index) { return names[index]; }
  String column(int index) { return columns[index]; }
  boolean unique(int index) { return unique[index]; }
  short ordinal(int index) { return ordinals[index]; }
  void clear(int index) { names[index] = null; columns[index] = null; }

  private void reserve(int count) throws SQLException {
    if (count <= names.length) return;
    int capacity = Math.min(MAXIMUM_ROWS, Math.max(count, names.length << 1));
    try {
      String[] grownNames = java.util.Arrays.copyOf(names, capacity);
      String[] grownColumns = java.util.Arrays.copyOf(columns, capacity);
      boolean[] grownUnique = java.util.Arrays.copyOf(unique, capacity);
      short[] grownOrdinals = java.util.Arrays.copyOf(ordinals, capacity);
      names = grownNames;
      columns = grownColumns;
      unique = grownUnique;
      ordinals = grownOrdinals;
    } catch (OutOfMemoryError failure) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "reserve index metadata");
    }
  }
}
