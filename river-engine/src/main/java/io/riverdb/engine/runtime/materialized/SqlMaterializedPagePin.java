package io.riverdb.engine.runtime.materialized;

import java.nio.ByteBuffer;

/**
 * Caller-owned pin token. Its buffer belongs to the pool and is valid only until unpin returns.
 */
public final class SqlMaterializedPagePin {
  private SqlMaterializedPagePool pool;
  private ByteBuffer buffer;
  private long owner;
  private long generation;
  private int frame = -1;
  private boolean dirty;

  public boolean active() { return pool != null; }
  public ByteBuffer buffer() { return buffer; }
  public long owner() { return owner; }
  boolean dirty() { return dirty; }
  void markDirty() { dirty = true; }

  void attach(
      SqlMaterializedPagePool source, int frameIndex, long frameGeneration,
      long ownerIdentity, ByteBuffer page, boolean dirtyPage) {
    pool = source;
    frame = frameIndex;
    generation = frameGeneration;
    owner = ownerIdentity;
    buffer = page;
    dirty = dirtyPage;
  }

  void clear() {
    pool = null;
    buffer = null;
    owner = 0;
    generation = 0;
    frame = -1;
    dirty = false;
  }

  SqlMaterializedPagePool pool() { return pool; }
  int frame() { return frame; }
  long generation() { return generation; }
}
