package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import java.sql.SQLException;
import java.util.Arrays;

/** Geometrically retained JDBC column-description rows. */
final class JdbcColumnMetadataRows {
  private String[] names = new String[8];
  private int[] descriptors = new int[8];
  private boolean[] nullable = new boolean[8];

  void reserve(int count) throws SQLException {
    if (count <= names.length) return;
    int capacity = Math.min(SqlShapeLimits.MAX_RESULT_COLUMNS,
        Math.max(count, names.length << 1));
    try {
      String[] grownNames = Arrays.copyOf(names, capacity);
      int[] grownDescriptors = Arrays.copyOf(descriptors, capacity);
      boolean[] grownNullable = Arrays.copyOf(nullable, capacity);
      names = grownNames;
      descriptors = grownDescriptors;
      nullable = grownNullable;
    } catch (OutOfMemoryError failure) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "reserve column metadata");
    }
  }

  void set(int index, String name, int descriptor, boolean isNullable) {
    names[index] = name;
    descriptors[index] = descriptor;
    nullable[index] = isNullable;
  }

  String name(int index) { return names[index]; }
  int descriptor(int index) { return descriptors[index]; }
  boolean nullable(int index) { return nullable[index]; }
  void clear(int index) { names[index] = null; descriptors[index] = 0; nullable[index] = false; }
}
