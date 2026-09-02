package io.riverdb.engine.schema;

/** Deterministic conservative descriptor byte accounting. */
final class SchemaByteCharge {
  static final int OBJECT_HEADER_BYTES = 32;
  static final int ARRAY_HEADER_BYTES = 16;
  static final long MAXIMUM_CHARGE = 8L * 1024 * 1024;

  private SchemaByteCharge() {
  }

  static long align(long bytes) {
    return bytes + 7 & ~7L;
  }

  static long array(int elementBytes, int count) {
    return align(ARRAY_HEADER_BYTES + (long) elementBytes * count);
  }

  static long object(int primitiveBytes, int references) {
    return align(OBJECT_HEADER_BYTES + primitiveBytes + (long) Long.BYTES * references);
  }

  static long string(int characters) {
    return object(16, 1) + array(Character.BYTES, characters);
  }

  static boolean fits(long charge) {
    return charge >= 0 && charge <= MAXIMUM_CHARGE;
  }

  static long columnSet(int count, int nameBytes, int slots) {
    return object(0, 5)
        + array(Integer.BYTES, count) * 3
        + array(1, count)
        + array(1, nameBytes)
        + array(Integer.BYTES, slots)
        + object(0, 0);
  }

}
