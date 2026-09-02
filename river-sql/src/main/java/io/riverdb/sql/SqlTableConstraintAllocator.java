package io.riverdb.sql;

/** Injectable retained-array boundary for atomic constraint growth tests. */
interface SqlTableConstraintAllocator {
  SqlTableConstraintAllocator STANDARD = new SqlTableConstraintAllocator() {
    public byte[] bytes(int capacity) { return new byte[capacity]; }
    public int[] integers(int capacity) { return new int[capacity]; }
    public SqlIdentifier[] identifiers(int capacity) {
      SqlIdentifier[] result = new SqlIdentifier[capacity];
      for (int index = 0; index < capacity; index++) result[index] = new SqlIdentifier();
      return result;
    }
  };

  byte[] bytes(int capacity);
  int[] integers(int capacity);
  SqlIdentifier[] identifiers(int capacity);
}
