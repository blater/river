package io.riverdb.engine.table;

import java.nio.ByteBuffer;

/** Caller-owned pin token for the current or staged frame selected by an operation. */
final class IndexedOperationPage {
  static final int CURRENT_ARENA = 1;
  static final int STAGING_ARENA = 2;
  static final int PREPARED_ARENA = 3;

  private ByteBuffer payload;
  private int pageId;
  private int arena;
  private int frameSlot = -1;
  private long pageGeneration;

  void set(
      int id, ByteBuffer bytes, int sourceArena, int sourceSlot, long generation) {
    pageId = id;
    payload = bytes;
    arena = sourceArena;
    frameSlot = sourceSlot;
    pageGeneration = generation;
  }

  void reset() {
    pageId = 0;
    payload = null;
    arena = 0;
    frameSlot = -1;
    pageGeneration = 0;
  }

  boolean attached() { return payload != null; }
  ByteBuffer payload() { return payload; }
  int pageId() { return pageId; }
  int arena() { return arena; }
  int frameSlot() { return frameSlot; }
  long pageGeneration() { return pageGeneration; }
}
