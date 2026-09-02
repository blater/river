package io.riverdb.sql;

import io.riverdb.base.sql.SqlShapeLimits;
import java.util.Arrays;

/** Reusable actual-count ORDER BY identifiers and directions. */
final class SqlOrderByList {
  private SqlIdentifier[] names = names(8);
  private SqlIdentifier[] qualifiers = names(8);
  private boolean[] descending = new boolean[8];
  private int count;

  SqlIdentifier append() {
    if (count >= SqlShapeLimits.MAX_ORDER_BY_EXPRESSIONS || !ensure(count + 1)) return null;
    names[count].reset();
    qualifiers[count].reset();
    descending[count] = false;
    return names[count++];
  }

  void reset() {
    for (int index = 0; index < count; index++) {
      names[index].reset();
      qualifiers[index].reset();
      descending[index] = false;
    }
    count = 0;
  }

  boolean copyFrom(SqlOrderByList source) {
    reset();
    if (source == null || !ensure(source.count)) return false;
    for (int index = 0; index < source.count; index++) {
      names[index].copyFrom(source.names[index]);
      qualifiers[index].copyFrom(source.qualifiers[index]);
      descending[index] = source.descending[index];
    }
    count = source.count;
    return true;
  }

  int count() { return count; }
  SqlIdentifier name(int index) { return index >= 0 && index < count ? names[index] : null; }
  SqlIdentifier qualifier(int index) {
    return index >= 0 && index < count ? qualifiers[index] : null;
  }
  boolean descending(int index) { return index >= 0 && index < count && descending[index]; }
  void descending(int index, boolean value) { if (index >= 0 && index < count) descending[index] = value; }

  private boolean ensure(int required) {
    if (required <= names.length) return true;
    int capacity = Math.min(SqlShapeLimits.MAX_ORDER_BY_EXPRESSIONS, names.length * 2);
    try {
      SqlIdentifier[] nextNames = Arrays.copyOf(names, capacity);
      SqlIdentifier[] nextQualifiers = Arrays.copyOf(qualifiers, capacity);
      boolean[] nextDescending = Arrays.copyOf(descending, capacity);
      for (int index = names.length; index < capacity; index++) {
        nextNames[index] = new SqlIdentifier();
        nextQualifiers[index] = new SqlIdentifier();
      }
      names = nextNames;
      qualifiers = nextQualifiers;
      descending = nextDescending;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  private static SqlIdentifier[] names(int capacity) {
    SqlIdentifier[] result = new SqlIdentifier[capacity];
    for (int index = 0; index < capacity; index++) result[index] = new SqlIdentifier();
    return result;
  }
}
