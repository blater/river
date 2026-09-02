package io.riverdb.engine.schema;

/** Injectable primitive-array allocation boundary for packed descriptor admission. */
interface PackedColumnArrayAllocator {
  PackedColumnArrayAllocator STANDARD = new PackedColumnArrayAllocator() {
    @Override
    public int[] integers(int size) { return new int[size]; }

    @Override
    public byte[] bytes(int size) { return new byte[size]; }
  };

  int[] integers(int size);
  byte[] bytes(int size);
}
