package io.riverdb.engine.checkpoint;

/** Decoded sparse-generation reference carried beside checkpoint authority metadata. */
final class CheckpointManifestVersion {
  private int pageCount;
  private long fileBytes;
  private int slot = -1;
  private int cleanupSlot = -1;

  void set(int pages, long bytes, int activeSlot, int retiredSlot) {
    pageCount = pages;
    fileBytes = bytes;
    slot = activeSlot;
    cleanupSlot = retiredSlot;
  }

  int pageCount() { return pageCount; }
  long fileBytes() { return fileBytes; }
  boolean available() { return pageCount > 0; }
  int slot() { return slot; }
  int cleanupSlot() { return cleanupSlot; }
}
