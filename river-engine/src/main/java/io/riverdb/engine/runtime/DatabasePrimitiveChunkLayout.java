package io.riverdb.engine.runtime;

/** Canonical conservative retained-byte model for lazily allocated primitive radix chunks. */
public final class DatabasePrimitiveChunkLayout {
  public static final int PAGE_SHIFT = 8;
  public static final int PAGE_SIZE = 1 << PAGE_SHIFT;
  public static final int PAGE_MASK = PAGE_SIZE - 1;
  public static final int DIRECTORY_BITS = 8;
  public static final int DIRECTORY_SIZE = 1 << DIRECTORY_BITS;
  public static final int DIRECTORY_MASK = DIRECTORY_SIZE - 1;

  // Deliberately conservative for supported 64-bit JVMs, including uncompressed references.
  private static final long ARRAY_HEADER_BYTES = 32;
  private static final long REFERENCE_BYTES = 8;

  private DatabasePrimitiveChunkLayout() {}

  public static int rootLength(int maximumElements) {
    if (maximumElements < 0) return -1;
    long pages = ((long) maximumElements + PAGE_MASK) >>> PAGE_SHIFT;
    return (int) ((pages + 65_535L) >>> 16);
  }

  public static long retainedBytes(
      int maximumElements, int requiredElements, int elementBytes) {
    if (maximumElements < 0 || requiredElements < 0
        || requiredElements > maximumElements || elementBytes <= 0) return -1;
    if (requiredElements == 0) return 0;
    long pages = ((long) requiredElements + PAGE_MASK) >>> PAGE_SHIFT;
    long branches = (pages + 65_535L) >>> 16;
    long directories = (pages + DIRECTORY_MASK) >>> DIRECTORY_BITS;
    long leafBytes = pages * (PAGE_SIZE * (long) elementBytes + ARRAY_HEADER_BYTES);
    long directoryBytes = (branches + directories)
        * (DIRECTORY_SIZE * REFERENCE_BYTES + ARRAY_HEADER_BYTES);
    long rootBytes = rootLength(maximumElements) * REFERENCE_BYTES + ARRAY_HEADER_BYTES;
    return leafBytes > Long.MAX_VALUE - directoryBytes
            || leafBytes + directoryBytes > Long.MAX_VALUE - rootBytes
        ? -1 : leafBytes + directoryBytes + rootBytes;
  }
}
