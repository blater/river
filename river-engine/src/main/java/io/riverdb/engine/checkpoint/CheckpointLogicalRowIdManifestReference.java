package io.riverdb.engine.checkpoint;

/** Manifest-owned reference to one immutable logical-row-floor generation. */
final class CheckpointLogicalRowIdManifestReference {
  private int count;
  private long fileBytes;
  private int digest;
  private int slot = -1;
  private int cleanupSlot = -1;

  void set(int entries, long bytes, int expectedDigest, int activeSlot, int retiredSlot) {
    count = entries;
    fileBytes = bytes;
    digest = expectedDigest;
    slot = activeSlot;
    cleanupSlot = retiredSlot;
  }

  int count() { return count; }
  long fileBytes() { return fileBytes; }
  int digest() { return digest; }
  int slot() { return slot; }
  int cleanupSlot() { return cleanupSlot; }
  boolean available() { return slot >= 0; }
}
